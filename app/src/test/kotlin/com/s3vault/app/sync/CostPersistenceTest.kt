package com.s3vault.app.sync

import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.cost.StorageInventory
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CostPersistenceTest {
    private lateinit var env: TestEnv
    private val saved = mutableListOf<StorageInventory>()
    private val config = S3Config("eu-west-1", "mi-bucket", "mi/prefijo/", AuthMethod.STATIC)

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private fun catalog() = RemoteCatalog(env.db, nowMs = { env.now }, inventoryStore = InventoryStore { saved += it })
    private fun ctx() = RemoteContext(env.remote, env.keys, config)
    private fun put(name: String, size: Int, cls: String = "STANDARD", key: String = env.keys.photoKey("%032x".format(name.hashCode()), "%064x".format(name.hashCode()), name)): String {
        env.remote.objects[key] = FakeRemote.Obj(ByteArray(size), "", "e-$name", cls)
        return key
    }

    // ------------------------------------------------------------------ the sweep that already happens feeds the estimate

    @Test fun `the listing a refresh already makes is turned into an inventory with no extra request`() = runBlocking {
        put("a.jpg", 4_000_000)
        put("b.jpg", 9_000_000, "GLACIER_IR")
        put("c.jpg", 50_000)

        catalog().refresh(ctx())

        val inv = saved.single()
        assertEquals(3L, inv.totalObjects)
        assertEquals(13_050_000L, inv.totalBytes)
        assertEquals(setOf("STANDARD", "GLACIER_IR"), inv.classes.map { it.storageClass }.toSet())
        assertEquals("mi-bucket", inv.bucket); assertEquals("eu-west-1", inv.region); assertEquals("mi/prefijo/", inv.prefix)
        assertEquals(env.now, inv.takenAtMs)
        assertEquals("one request per 1000 objects, at least one", 1, inv.listRequests)
        assertTrue(inv.complete)
        assertEquals("the remote library cache is still filled as before", 3, env.db.remote().getAll().size)
    }

    @Test fun `objects that are not backed-up photos are in the inventory but not in the library`() = runBlocking {
        put("a.jpg", 1_000)
        put("notes.txt", 7_000, key = env.keys.photosPrefix + "notes.txt")

        catalog().refresh(ctx())

        assertEquals(2L, saved.single().totalObjects)
        assertEquals(1L, saved.single().photoObjects)
        assertEquals(7_000L, saved.single().unrecognizedBytes)
        assertEquals(1, env.db.remote().getAll().size)
    }

    @Test fun `a failed listing saves no inventory and leaves the previous one alone`() {
        put("a.jpg", 1_000)
        env.remote.listFailure = RemoteException(RemoteErrorKind.NETWORK)

        assertThrows(RemoteException::class.java) { runBlocking { catalog().refresh(ctx()) } }

        assertTrue(saved.isEmpty())
    }

    @Test fun `the sweep of a backup run refreshes the inventory too`() = runBlocking {
        env.index(env.scanned(1))
        env.enqueue(1)
        val engineCatalog = RemoteCatalog(env.db, nowMs = { env.now }, inventoryStore = InventoryStore { saved += it })
        BackupEngine(env.db, RemoteContextProvider { ctx() }, env.creds, env.reader, env.progress, env.events, engineCatalog, nowMs = { env.now }).run(1)

        assertEquals("one LIST sweep at the start of the run", 1, saved.size)
        assertEquals("taken before the upload, which is why the screen tracks uploads since then", 0L, saved.single().totalObjects)
    }

    // ------------------------------------------------------------------ storing it

    @Test fun `the inventory is stored and read back, and a failed attempt does not replace it`() = runBlocking {
        val settings = AppSettings(env.context)
        settings.clearS3Config()
        put("a.jpg", 2_000)
        catalog().refresh(ctx())
        val inv = saved.single()

        settings.saveInventory(inv)
        assertEquals(inv, settings.inventory.first())
        assertEquals(null, settings.inventoryAttempt.first()!!.error)

        settings.saveInventoryFailure(env.now + 1, "Error temporal de red. Se reintentará.")
        assertEquals("the last complete inventory is kept", inv, settings.inventory.first())
        assertEquals("Error temporal de red. Se reintentará.", settings.inventoryAttempt.first()!!.error)

        settings.saveInventory(inv.copy(takenAtMs = env.now + 2))
        assertNull("a later success clears the failure", settings.inventoryAttempt.first()!!.error)
    }

    @Test fun `disconnecting forgets the inventory`() = runBlocking {
        val settings = AppSettings(env.context)
        put("a.jpg", 2_000)
        catalog().refresh(ctx())
        settings.saveInventory(saved.single())
        assertNotNull(settings.inventory.first())

        settings.clearS3Config()

        assertNull(settings.inventory.first())
        assertNull(settings.inventoryAttempt.first())
    }

    // ------------------------------------------------------------------ what the app did since the inventory

    @Test fun `uploads since a moment are counted from the events the app already logs`() = runBlocking {
        env.index(env.scanned(1), env.scanned(2))
        env.enqueue(1, 2)
        env.engine().run(1)

        assertEquals(2, env.db.events().observeUploadsSince(0).first())
        assertEquals("events are counted strictly after the inventory", 0, env.db.events().observeUploadsSince(System.currentTimeMillis() + 60_000).first())
    }

    @Test fun `a cloud deletion logs how many objects it really removed`() = runBlocking {
        val k1 = put("a.jpg", 10); val k2 = put("b.jpg", 10); val k3 = put("c.jpg", 10)
        catalog().refresh(ctx())
        env.remote.deleteKeyFailures[k3] = RemoteErrorKind.THROTTLED

        env.cloudDeletion().delete(listOf(k1, k2, k3))

        assertEquals("only what S3 confirmed", 2, env.db.events().observeCloudDeletionsSince(0).first())
        assertEquals(0, env.db.events().observeCloudDeletionsSince(System.currentTimeMillis() + 60_000).first())
    }

    @Test fun `a cancelled deletion still counts the batches that went through`() = runBlocking {
        val keys = (1..150).map { put("p$it.jpg", 10) }
        catalog().refresh(ctx())
        val controller = env.cloudDeletion()
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) { controller.delete(keys) { done, _ -> if (done >= 100) job.cancel() } }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(100, env.db.events().observeCloudDeletionsSince(0).first())
    }

    @Test fun `deleting nothing logs nothing`() = runBlocking {
        env.cloudDeletion().delete(emptyList())
        assertEquals(0, env.db.events().observeCloudDeletionsSince(0).first())
    }
}
