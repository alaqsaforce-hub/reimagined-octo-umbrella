package com.voicedock.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.data.database.AppDatabase
import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import com.voicedock.ai.data.database.LegacyImport
import com.voicedock.ai.data.database.MemoryRow
import com.voicedock.ai.data.database.Migrations
import com.voicedock.ai.memory.MemoryStore
import com.voicedock.ai.notify.NotifEntry
import com.voicedock.ai.notify.NotifStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomPersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, "persist-test.db").allowMainThreadQueries().build()

    @Test fun draftSurvivesDatabaseReopen() = runBlocking {
        ctx.deleteDatabase("persist-test.db")
        val d = DictationDraft(
            id = "d1", createdAt = 1, updatedAt = 2, rawText = "আমি আজ অফিসে যাব", formattedText = "আমি আজ অফিসে যাব।",
            segmentsJson = """[{"text":"আমি আজ অফিসে যাব"}]""", status = DraftStatus.ACTIVE, language = "bn-BD", engine = "fake",
        )
        open().apply { drafts().upsert(d); close() }

        val db2 = open()
        val back = db2.drafts().latestUnfinished()
        assertEquals(d, back)
        // status changes are updates, not duplicates
        db2.drafts().upsert(d.copy(status = DraftStatus.SAVED, updatedAt = 3))
        assertNull(db2.drafts().latestUnfinished())
        assertEquals(1, db2.drafts().count())
        assertEquals(DraftStatus.SAVED, db2.drafts().observeRecent(10).first().single().status)
        db2.close()
    }

    /**
     * v0.10.0 — the real v2 → v3 upgrade. The v2 database is created byte-for-byte from
     * app/schemas/.../2.json (same DDL, same identity hash), then opened at v3 through
     * [Migrations.MIGRATION_2_3]: the user's drafts must survive and the two new tables must work.
     */
    @Test fun migration2To3KeepsDraftsAndAddsAssistantTables() {
        val name = "migrate-2-3.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name)
        file.parentFile?.mkdirs()

        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        listOf(
            "CREATE TABLE IF NOT EXISTS `dictation_drafts` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `rawText` TEXT NOT NULL, `formattedText` TEXT NOT NULL, `segmentsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `language` TEXT NOT NULL, `engine` TEXT NOT NULL, `destination` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_dictation_drafts_status` ON `dictation_drafts` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_dictation_drafts_updatedAt` ON `dictation_drafts` (`updatedAt`)",
            "CREATE TABLE IF NOT EXISTS `kb_documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `mime` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `charCount` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_kb_documents_updatedAt` ON `kb_documents` (`updatedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_kb_documents_contentHash` ON `kb_documents` (`contentHash`)",
            "CREATE TABLE IF NOT EXISTS `kb_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `docId` TEXT NOT NULL, `ord` INTEGER NOT NULL, `text` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_kb_chunks_docId` ON `kb_chunks` (`docId`)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `kb_chunks_fts` USING FTS4(`text` TEXT NOT NULL)",
        ).forEach { legacy.execSQL(it) }
        legacy.execSQL(
            "INSERT INTO dictation_drafts (id, createdAt, updatedAt, rawText, formattedText, segmentsJson, status, language, engine, destination) " +
                "VALUES ('legacy', 1, 2, 'পুরনো লেখা', 'পুরনো লেখা।', '[]', 'SAVED', 'bn-BD', 'fake', '')",
        )
        legacy.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        legacy.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '0cdc7fe34b3c72395a50dcb294b8b494')")
        legacy.version = 2
        legacy.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()

        // 1. the old data is still there after the upgrade
        val draft = runBlocking { db.drafts().get("legacy") }
        assertEquals("পুরনো লেখা", draft?.rawText)
        // 2. the new assistant tables exist and behave like the JSON stores did
        runBlocking {
            db.assistant().upsertMemory(MemoryRow("m1", "কাল ৩টায় মিটিং", 5, "voice"))
            assertEquals(1, db.assistant().memoryCount())
            db.assistant().upsertNotif(
                com.voicedock.ai.data.database.NotifRow("k1", "com.whatsapp", "WhatsApp", "রহিম", "আসছি", 10, true, false),
            )
            assertEquals(1, db.assistant().notifs(10).size)
        }
        db.close()
        ctx.deleteDatabase(name)
    }

    /** v0.10.0 — the v0.7-v0.9 JSON files are copied into Room exactly once and never lost. */
    @Test fun legacyJsonIsImportedOnceAndKeptOnDisk() {
        val dir = File(ctx.filesDir, "legacy-import-test").apply { deleteRecursively(); mkdirs() }
        val json = MemoryStore(dir) // no dao -> the old JSON path
        val a = json.add("কাল ৩টায় মিটিং", now = 100)
        val b = json.add("দুধ আনতে হবে", now = 200)
        val notifs = NotifStore(dir)
        notifs.upsert(
            NotifEntry(key = "k1", pkg = "com.whatsapp", appLabel = "WhatsApp", sender = "রহিম", text = "আসছি", at = 10, canReply = true),
        )

        ctx.deleteDatabase("import-test.db")
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "import-test.db").allowMainThreadQueries().build()

        LegacyImport.runOnce(ctx, db, dir)

        val roomMemory = runBlocking { db.assistant().memories(MemoryStore.MAX_ENTRIES) }
        assertEquals(2, roomMemory.size)
        assertEquals(setOf(a?.id, b?.id), roomMemory.map { it.id }.toSet())
        assertEquals(1, runBlocking { db.assistant().notifCount() })

        // the JSON files are renamed, not deleted — nothing is destroyed by the migration
        assertFalse(File(dir, MemoryStore.FILE_NAME).exists())
        assertTrue(File(dir, MemoryStore.FILE_NAME + ".imported").exists())
        assertTrue(File(dir, NotifStore.FILE_NAME + ".imported").exists())

        // a second run is a no-op (flag), so nothing is duplicated
        LegacyImport.runOnce(ctx, db, dir)
        assertEquals(2, runBlocking { db.assistant().memories(MemoryStore.MAX_ENTRIES) }.size)
        db.close()
    }

    /** The store API is the same over Room: newest first, blank ignored, forget works. */
    @Test fun memoryStoreOverRoomBehavesLikeTheJsonOne() {
        ctx.deleteDatabase("store-room.db")
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "store-room.db").allowMainThreadQueries().build()
        val dir = File(ctx.filesDir, "store-room-dir").apply { mkdirs() }
        val store = MemoryStore(dir, db.assistant())

        val first = store.add("প্রথম কথা", now = 1)
        store.add("দ্বিতীয় কথা", now = 2)
        assertNull(store.add("   ", now = 3))
        assertEquals(listOf("দ্বিতীয় কথা", "প্রথম কথা"), store.all().map { it.text })
        assertTrue(store.forget(first!!.id))
        assertFalse(store.forget("nope"))
        assertEquals(1, store.all().size)
        store.clear()
        assertEquals(0, store.all().size)
        db.close()
    }

}
