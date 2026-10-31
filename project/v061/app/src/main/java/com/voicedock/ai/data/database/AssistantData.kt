package com.voicedock.ai.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * v0.10.0 — memory and the notification log move from JSON files to Room.
 *
 * Why: both were capped, rewritten in full on every change and read synchronously. Room gives the same
 * guarantees with an indexed query, and it means one database (and one backup story) instead of a mix of
 * files and tables. The JSON files are imported once by [LegacyImport] and then left untouched, so an
 * existing install keeps every memory and message it had.
 */
@Entity(tableName = "memory_entries", indices = [Index("createdAt")])
data class MemoryRow(
    @PrimaryKey val id: String,
    val text: String,
    val createdAt: Long,
    val source: String,
)

@Entity(tableName = "notif_entries", indices = [Index("at"), Index("sender")])
data class NotifRow(
    @PrimaryKey val key: String,
    val pkg: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val at: Long,
    val canReply: Boolean,
    val summary: Boolean,
)

@Dao
interface AssistantDao {

    // ---- memory -------------------------------------------------------------------------------
    @Upsert suspend fun upsertMemory(row: MemoryRow)

    @Query("SELECT * FROM memory_entries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun memories(limit: Int): List<MemoryRow>

    @Query("SELECT COUNT(*) FROM memory_entries")
    suspend fun memoryCount(): Int

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun forgetMemory(id: String)

    @Query("DELETE FROM memory_entries")
    suspend fun clearMemory()

    /** Keeps the store bounded exactly like the JSON version did (newest [max] rows). */
    @Query("DELETE FROM memory_entries WHERE id NOT IN (SELECT id FROM memory_entries ORDER BY createdAt DESC LIMIT :max)")
    suspend fun trimMemory(max: Int)

    @Transaction
    suspend fun addMemory(row: MemoryRow, max: Int) { upsertMemory(row); trimMemory(max) }

    // ---- notifications ------------------------------------------------------------------------
    @Upsert suspend fun upsertNotif(row: NotifRow)

    @Query("SELECT * FROM notif_entries ORDER BY at DESC LIMIT :limit")
    suspend fun notifs(limit: Int): List<NotifRow>

    @Query("SELECT * FROM notif_entries WHERE at >= :from ORDER BY at DESC")
    suspend fun notifsSince(from: Long): List<NotifRow>

    @Query("SELECT COUNT(*) FROM notif_entries")
    suspend fun notifCount(): Int

    @Query("DELETE FROM notif_entries WHERE `key` = :key")
    suspend fun deleteNotif(key: String)

    @Query("DELETE FROM notif_entries")
    suspend fun clearNotifs()

    @Query("DELETE FROM notif_entries WHERE `key` NOT IN (SELECT `key` FROM notif_entries ORDER BY at DESC LIMIT :max)")
    suspend fun trimNotifs(max: Int)

    @Transaction
    suspend fun addNotif(row: NotifRow, max: Int) { upsertNotif(row); trimNotifs(max) }
}
