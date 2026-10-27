package com.s3vault.app.sync

import com.s3vault.app.data.local.FaceEntity
import com.s3vault.app.data.local.PersonEntity
import com.s3vault.app.domain.faces.AesGcmFaceVault
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceMath
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.FaceTestKit
import com.s3vault.app.domain.faces.FaceVectorCodec
import com.s3vault.app.domain.faces.MemoryDataKeyStore
import com.s3vault.app.domain.faces.PersonState
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random

/**
 * A large library on the real tables: thousands of faces already grouped and a batch of new ones arriving. The point is the shape of
 * the cost: placing new faces and forming groups must depend on the new faces and the number of groups, not on comparing every face
 * with every other. The bounds are generous (this runs on a laptop under a test runner); the measured numbers go to a report file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceScaleTest {
    private lateinit var env: TestEnv
    private val vault = AesGcmFaceVault(MemoryDataKeyStore())
    private val rnd = Random(77)

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    @Test fun `placing a batch of new faces among thousands of grouped ones does not compare every face with every face`() = runBlocking {
        val people = 300; val perPerson = 40; val strangers = 3000; val batchSize = 600
        val dao = env.db.faces()
        val dirs = (0 until people).map { FaceTestKit.randomUnit(rnd) }
        val now = env.now
        // One media row per face (a photo has one face here): the index only needs the rows to exist.
        val ids = (1L..(people * perPerson + strangers + batchSize).toLong()).toList()
        env.db.openHelper.writableDatabase.let { db ->
            db.beginTransaction()
            try {
                val st = db.compileStatement("INSERT INTO media_items (mediaStoreId, uri, kind, displayName, mimeType, sizeBytes, dateModifiedSec, dateTakenMs, width, height, folderName, assetId, present, syncState, lastErrorPermanent, attempts, thumbnailUploaded, backupExcluded, dayKey, dateSource, isFavorite, dateAddedSec) VALUES (?, 'u', 'IMAGE', 'n', 'image/jpeg', 1, 1, ?, 1, 1, 'f', ?, 1, 'LOCAL_ONLY', 0, 0, 0, 0, 0, 'CAPTURE', 0, 0)")
                for (id in ids) { st.bindLong(1, id); st.bindLong(2, id); st.bindString(3, "a$id"); st.executeInsert() }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        var photo = 0L
        fun face(v: FloatArray, personId: Long?, assignment: FaceAssignment) = FaceEntity(
            mediaStoreId = ++photo, boxLeft = 0.2f, boxTop = 0.2f, boxRight = 0.5f, boxBottom = 0.6f, detScore = 0.9f, quality = 0.8f, modelId = FaceModel.MODEL_ID,
            sealed = FaceVectorCodec.sealVector(vault, v), personId = personId, assignment = assignment, matchScore = 0.8f, margin = 0.4f, createdAtMs = now,
        )
        val built = System.nanoTime()
        for ((i, d) in dirs.withIndex()) {
            val pid = dao.insertPerson(PersonEntity(ordinal = i + 1, state = PersonState.CONFIRMED, createdAtMs = now, updatedAtMs = now))
            dao.insertFaces((0 until perPerson).map { face(FaceTestKit.at(d, 0.88f, rnd), pid, FaceAssignment.AUTO) })
        }
        dao.insertFaces((0 until strangers).map { face(FaceTestKit.randomUnit(rnd), null, FaceAssignment.NONE) })
        val grouping = FaceGroupingStore(env.db, vault, nowMs = { env.now })
        env.db.withTransaction { grouping.refreshCenters(dao.people().map { it.id }) }
        val setupMs = (System.nanoTime() - built) / 1_000_000

        // The new batch: two thirds are people already known, the rest strangers.
        val fresh = (0 until batchSize).map { i -> if (i % 3 != 0) face(FaceTestKit.at(dirs[rnd.nextInt(people)], 0.88f, rnd), null, FaceAssignment.NONE) else face(FaceTestKit.randomUnit(rnd), null, FaceAssignment.NONE) }
        val newIds = dao.insertFaces(fresh)

        val t0 = System.nanoTime()
        val r = grouping.integrate(newIds)
        val integrateMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val merged = grouping.autoMerge()
        val mergeMs = (System.nanoTime() - t1) / 1_000_000
        val t2 = System.nanoTime()
        val offers = grouping.mergeSuggestions()
        val offersMs = (System.nanoTime() - t2) / 1_000_000

        File("build").mkdirs()
        File("build/face-scale.txt").writeText(
            "groups=$people grouped=${people * perPerson} pool=$strangers newBatch=$batchSize | setup ${setupMs}ms | integrate ${integrateMs}ms (placed ${r.placed}, suggested ${r.suggested}, new groups ${r.newPeople}) | " +
                "autoMerge ${mergeMs}ms ($merged merged) | mergeSuggestions ${offersMs}ms (${offers.size})\n",
        )
        assertTrue("known people were placed: ${r.placed}", r.placed >= batchSize * 2 / 3 * 8 / 10)
        // Wrong placements: a face of person k that landed in another group.
        val wrong = newIds.indices.filter { i -> i % 3 != 0 }.count { i ->
            val placed = dao.face(newIds[i])!!.personId ?: return@count false
            FaceMath.dot(FaceVectorCodec.openCenter(vault, dao.person(placed)!!.centroidSealed!!)!!, FaceVectorCodec.openVector(vault, dao.face(newIds[i])!!.sealed)!!) < 0.5f
        }
        assertEquals("nothing was placed in a group it does not resemble", 0, wrong)
        assertTrue("integrating $batchSize faces among ${people * perPerson + strangers} took ${integrateMs}ms", integrateMs < 20_000)
        assertTrue("merge pass took ${mergeMs}ms", mergeMs < 20_000)
    }

}
