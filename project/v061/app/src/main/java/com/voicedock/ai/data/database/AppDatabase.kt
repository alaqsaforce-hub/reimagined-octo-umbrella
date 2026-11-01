package com.voicedock.ai.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a dictation draft. Nothing is ever silently discarded: cancel/clear keep a recoverable row. */
enum class DraftStatus { ACTIVE, REVIEW, INSERTED, SAVED, CANCELLED, CLEARED }

@Entity(
    tableName = "dictation_drafts",
    indices = [Index("status"), Index("updatedAt")],
)
data class DictationDraft(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Recognised text exactly as spoken (segments joined). */
    val rawText: String,
    /** Cleaned/edited text the user reviews and inserts. */
    val formattedText: String,
    /** JSON list of Segment (keeps provisional markers so a restored session merges correctly). */
    val segmentsJson: String,
    val status: DraftStatus,
    val language: String,
    val engine: String,
    /** Where the text went (e.g. "accessibility:com.whatsapp", "clipboard"). */
    val destination: String = "",
)

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(draft: DictationDraft)

    @Query("SELECT * FROM dictation_drafts WHERE id = :id")
    suspend fun get(id: String): DictationDraft?

    @Query("SELECT * FROM dictation_drafts WHERE status = 'ACTIVE' OR status = 'REVIEW' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestUnfinished(): DictationDraft?

    @Query("SELECT * FROM dictation_drafts ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DictationDraft>>

    @Query("SELECT COUNT(*) FROM dictation_drafts")
    suspend fun count(): Int

    /** v0.7.0 backup: every draft, oldest first (a suspend list, unlike [observeRecent]). */
    @Query("SELECT * FROM dictation_drafts ORDER BY createdAt ASC")
    suspend fun all(): List<DictationDraft>

    @Query("DELETE FROM dictation_drafts WHERE id = :id")
    suspend fun delete(id: String)
}

/** v1.2.0 — a spoken reminder ("৩টায় মনে করিয়ে দাও পানি খেতে"). */
enum class ReminderStatus { PENDING, DONE, CANCELLED }

@Entity(
    tableName = "reminders",
    indices = [Index("triggerAt"), Index("status")],
)
data class ReminderRow(
    @PrimaryKey val id: String,
    val text: String,
    /** Epoch millis of the moment the reminder must speak up. */
    val triggerAt: Long,
    val createdAt: Long,
    val status: ReminderStatus,
    /** "voice" / "text" / "notification" — where it came from, for the log and for debugging. */
    val source: String = "",
)

@Dao
interface ReminderDao {
    @Upsert
    suspend fun upsert(r: ReminderRow)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderRow?

    /** Everything still to come, soonest first. */
    @Query("SELECT * FROM reminders WHERE status = 'PENDING' ORDER BY triggerAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<ReminderRow>

    @Query("SELECT * FROM reminders WHERE status = 'PENDING' ORDER BY triggerAt ASC LIMIT :limit")
    fun observePending(limit: Int = 50): Flow<List<ReminderRow>>

    @Query("SELECT * FROM reminders WHERE status = 'PENDING' AND triggerAt <= :at")
    suspend fun dueBefore(at: Long): List<ReminderRow>

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: ReminderStatus)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    /** Finished rows are pruned after a week so the table cannot grow forever. */
    @Query("DELETE FROM reminders WHERE status != 'PENDING' AND triggerAt < :before")
    suspend fun prune(before: Long)
}

/**
 * Migration strategy: every schema change bumps [version] and adds an explicit [Migration] to
 * [Migrations.ALL]; the exported schema JSON (app/schemas) is committed. No destructive fallback —
 * a missing migration must fail loudly instead of wiping the user's data.
 */
@Database(
    entities = [
        DictationDraft::class, KbDocument::class, KbChunk::class, KbChunkFts::class,
        MemoryRow::class, NotifRow::class, ReminderRow::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun drafts(): DraftDao
    abstract fun knowledge(): KnowledgeDao

    /** v0.10.0 — assistant memory + notification log (were JSON files). */
    abstract fun assistant(): AssistantDao

    /** v1.2.0 — reminders and other scheduled jobs. */
    abstract fun reminders(): ReminderDao

    companion object {
        const val NAME = "voicedock.db"
        fun build(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

object Migrations {
    /** v0.3.0: knowledge base (documents, chunks, FTS4 index with normalised text). SQL copied from schemas/2.json. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `kb_documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `mime` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `charCount` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "CREATE INDEX IF NOT EXISTS `index_kb_documents_updatedAt` ON `kb_documents` (`updatedAt`)",
                "CREATE INDEX IF NOT EXISTS `index_kb_documents_contentHash` ON `kb_documents` (`contentHash`)",
                "CREATE TABLE IF NOT EXISTS `kb_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `docId` TEXT NOT NULL, `ord` INTEGER NOT NULL, `text` TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_kb_chunks_docId` ON `kb_chunks` (`docId`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `kb_chunks_fts` USING FTS4(`text` TEXT NOT NULL)",
            ).forEach { db.execSQL(it) }
        }
    }

    /**
     * v0.10.0: assistant memory and the notification log become tables. The SQL is the exact DDL Room
     * generated for schema 3 (see app/schemas/3.json) — the JSON files themselves are imported once by
     * [LegacyImport], so no memory is lost by the move.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `memory_entries` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "CREATE INDEX IF NOT EXISTS `index_memory_entries_createdAt` ON `memory_entries` (`createdAt`)",
                "CREATE TABLE IF NOT EXISTS `notif_entries` (`key` TEXT NOT NULL, `pkg` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `sender` TEXT NOT NULL, `text` TEXT NOT NULL, `at` INTEGER NOT NULL, `canReply` INTEGER NOT NULL, `summary` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                "CREATE INDEX IF NOT EXISTS `index_notif_entries_at` ON `notif_entries` (`at`)",
                "CREATE INDEX IF NOT EXISTS `index_notif_entries_sender` ON `notif_entries` (`sender`)",
            ).forEach { db.execSQL(it) }
        }
    }

    /**
     * v1.2.0: reminders. DDL copied from the generated `schemas/…/4.json` (Room validates the identity
     * hash on open, and [RoomPersistenceTest] upgrades a real v3 file, so a mismatch cannot ship).
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `triggerAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "CREATE INDEX IF NOT EXISTS `index_reminders_triggerAt` ON `reminders` (`triggerAt`)",
                "CREATE INDEX IF NOT EXISTS `index_reminders_status` ON `reminders` (`status`)",
            ).forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
