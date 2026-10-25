package com.s3vault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.MediaKinds
import java.time.ZoneId

@Database(
    entities = [
        MediaItemEntity::class,
        RemoteObjectEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        AlbumRemoteItemEntity::class,
        TransferEventEntity::class,
        JobEntity::class,
        IntegrityAuditEntity::class,
        IntegrityResultEntity::class,
        MediaAnalysisEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
        DuplicateIgnoredEntity::class,
        SmartAlbumEntity::class,
        SemanticEmbeddingEntity::class,
        PrivateVaultEntity::class,
        PrivateAlbumEntity::class,
        PrivateItemEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun remote(): RemoteDao
    abstract fun albums(): AlbumDao
    abstract fun events(): EventDao
    abstract fun jobs(): JobDao
    abstract fun integrity(): IntegrityDao
    abstract fun duplicates(): DuplicateDao
    abstract fun smartAlbums(): SmartAlbumDao
    abstract fun semantic(): SemanticDao
    abstract fun privateVault(): PrivateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "s3vault.db").addMigrations(*ALL_MIGRATIONS).build()

        /** Every migration, in order: the app and the tests open the database with exactly this list. */
        val ALL_MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

        /**
         * Private albums: the wrapped vault key, the albums (sealed names) and the protected pictures (sealed metadata). New tables
         * only; no existing row or file is touched, and nothing is moved into the vault until the user asks for it.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `private_vault` (`id` INTEGER NOT NULL, `wrappedKey` BLOB NOT NULL, `wrapIv` BLOB NOT NULL, " +
                        "`keyCheck` BLOB NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `private_albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nameSealed` BLOB NOT NULL, `createdAtMs` INTEGER NOT NULL)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `private_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `albumId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, " +
                        "`sealedBytes` INTEGER NOT NULL, `addedAtMs` INTEGER NOT NULL, `metaSealed` BLOB NOT NULL, " +
                        "FOREIGN KEY(`albumId`) REFERENCES `private_albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_private_items_albumId` ON `private_items` (`albumId`)")
            }
        }

        /** On-device semantic search: the vectors of the pictures, per model. Empty until the user turns the feature on. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `semantic_embeddings` (`mediaStoreId` INTEGER NOT NULL, `modelId` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`dateModifiedSec` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `scale` REAL NOT NULL, `vector` BLOB NOT NULL, `indexedAtMs` INTEGER NOT NULL, PRIMARY KEY(`mediaStoreId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_semantic_embeddings_modelId` ON `semantic_embeddings` (`modelId`)")
            }
        }

        /** Smart albums the user defines (a name and a saved question). The built-in ones need no storage. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smart_albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `filterJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL)",
                )
            }
        }

        /** Duplicate and similar-photo detection: fingerprints, the groups found, and the groups the user dismissed. Nothing existing changes. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_analysis` (`mediaStoreId` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `dateModifiedSec` INTEGER NOT NULL, " +
                        "`phash` INTEGER, `status` TEXT NOT NULL, `analyzedAtMs` INTEGER NOT NULL, PRIMARY KEY(`mediaStoreId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `duplicate_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `signature` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_duplicate_groups_signature` ON `duplicate_groups` (`signature`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_groups_kind` ON `duplicate_groups` (`kind`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `duplicate_members` (`groupId` INTEGER NOT NULL, `mediaStoreId` INTEGER NOT NULL, `distance` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `mediaStoreId`), FOREIGN KEY(`groupId`) REFERENCES `duplicate_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_members_mediaStoreId` ON `duplicate_members` (`mediaStoreId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `duplicate_ignored` (`signature` TEXT NOT NULL, `ignoredAtMs` INTEGER NOT NULL, PRIMARY KEY(`signature`))")
            }
        }

        /**
         * Favourite flag and date added, for the space-recovery rules and the smart albums. Existing rows get false and 0; the
         * first library scan after the upgrade reads the real values (the indexer treats a changed value as a change).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN dateAddedSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_present_dateAddedSec` ON `media_items` (`present`, `dateAddedSec`)")
            }
        }

        /**
         * The integrity auditor: a version marker (ETag) per backup and per listed object, and the audit tables. Existing rows
         * get NULL markers; the first audit records them as the starting point for later comparisons.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN remoteEtag TEXT")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN etag TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `integrity_audits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `planned` INTEGER NOT NULL, `checked` INTEGER NOT NULL, `verified` INTEGER NOT NULL, " +
                        "`missing` INTEGER NOT NULL, `changed` INTEGER NOT NULL, `unknown` INTEGER NOT NULL, `stale` INTEGER NOT NULL, `requests` INTEGER NOT NULL, `error` TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `integrity_results` (`remoteKey` TEXT NOT NULL, `verdict` TEXT NOT NULL, `level` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "`checkedAtMs` INTEGER NOT NULL, `deepCheckedAtMs` INTEGER, `expectedSize` INTEGER NOT NULL, `remoteSize` INTEGER, `auditId` INTEGER NOT NULL, PRIMARY KEY(`remoteKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_integrity_results_verdict` ON `integrity_results` (`verdict`)")
            }
        }

        /** The sync center keeps a one-row summary of the last run of each background job. Nothing existing changes. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `background_jobs` (`name` TEXT NOT NULL, `state` TEXT NOT NULL, `doneItems` INTEGER NOT NULL, " +
                        "`totalItems` INTEGER NOT NULL, `succeededItems` INTEGER NOT NULL, `failedItems` INTEGER NOT NULL, " +
                        "`startedAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `lastError` TEXT, PRIMARY KEY(`name`))",
                )
            }
        }

        /** Albums can hold photos that exist only in S3 (imported from the bucket's album file). Existing albums are untouched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_remote_items` (`albumId` INTEGER NOT NULL, `versionId` TEXT NOT NULL, " +
                        "`addedAtMs` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `versionId`), " +
                        "FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_remote_items_versionId` ON `album_remote_items` (`versionId`)")
            }
        }

        /**
         * Adds the date model of the smart gallery (day keys, where each date comes from, object kind) and the indexes that
         * make listing and grouping fast. Existing rows keep their date and get a day key in the current zone; the first
         * library scan after the update corrects the date source of local files.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN dayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN dateSource TEXT NOT NULL DEFAULT 'CAPTURE'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_present_dateTakenMs ON media_items (present, dateTakenMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_present_dayKey ON media_items (present, dayKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_remoteKey ON media_items (remoteKey)")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN effectiveDateMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN dateSource TEXT NOT NULL DEFAULT 'FALLBACK'")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN dayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN uploadDayKey INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN mediaKind TEXT")
                db.execSQL("ALTER TABLE remote_objects ADD COLUMN metaChecked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_objects_dayKey_effectiveDateMs ON remote_objects (dayKey, effectiveDateMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_objects_uploadDayKey ON remote_objects (uploadDayKey)")

                val zone = ZoneId.systemDefault()
                val local = ArrayList<Pair<Long, Long>>()
                db.query("SELECT mediaStoreId, dateTakenMs FROM media_items").use { c -> while (c.moveToNext()) local += c.getLong(0) to c.getLong(1) }
                val setDay = db.compileStatement("UPDATE media_items SET dayKey = ? WHERE mediaStoreId = ?")
                for ((id, ms) in local) {
                    setDay.bindLong(1, DateKeys.dayKey(ms, zone).toLong()); setDay.bindLong(2, id); setDay.executeUpdateDelete()
                }
                setDay.close()

                db.execSQL("UPDATE remote_objects SET effectiveDateMs = lastModifiedMs")
                val remote = ArrayList<Triple<String, String, Long>>()
                db.query("SELECT `key`, displayName, lastModifiedMs FROM remote_objects").use { c ->
                    while (c.moveToNext()) remote += Triple(c.getString(0), c.getString(1), c.getLong(2))
                }
                val setRemote = db.compileStatement("UPDATE remote_objects SET uploadDayKey = ?, mediaKind = ? WHERE `key` = ?")
                for ((key, name, uploaded) in remote) {
                    setRemote.bindLong(1, DateKeys.dayKey(uploaded, zone).toLong())
                    MediaKinds.fromName(name)?.let { setRemote.bindString(2, it.name) } ?: setRemote.bindNull(2)
                    setRemote.bindString(3, key)
                    setRemote.executeUpdateDelete()
                }
                setRemote.close()
            }
        }

        /** Adds the preview-upload marker and the "deleted from S3 on purpose" flag. Existing rows keep both off. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailUploaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_items ADD COLUMN backupExcluded INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
