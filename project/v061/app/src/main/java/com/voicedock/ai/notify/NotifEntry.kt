package com.voicedock.ai.notify

import com.voicedock.ai.data.database.AssistantDao
import com.voicedock.ai.data.database.NotifRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.8.0 — one captured notification, reduced to what a digest and a reply need.
 *
 * Nothing here is ever uploaded: it lives in the app's private directory, capped, and only while the
 * user keeps "Notification access" granted AND `assistantBackgroundEvents` switched on.
 */
@Serializable
data class NotifEntry(
    /** Notification key — also the dedup id (an edited notification keeps its key). */
    val key: String,
    val pkg: String,
    val appLabel: String,
    /** Conversation / sender name. Falls back to the app label when the app gives no name. */
    val sender: String,
    /** Message text, or "" when the app hides previews. Never invented. */
    val text: String,
    val at: Long,
    /** The app offered Direct Reply for this notification (RemoteInput present). */
    val canReply: Boolean = false,
    /** Only an ongoing/group summary — not a real message. */
    val summary: Boolean = false,
) {
    val hasText: Boolean get() = text.isNotBlank()
}

@Serializable
data class NotifDoc(val version: Int = 1, val entries: List<NotifEntry> = emptyList())

/**
 * v0.10.0 — Room when a [dao] is supplied, JSON otherwise (import/test path). Behaviour is unchanged:
 * newest first, an update of the same key replaces the row, capped at [MAX].
 */
class NotifStore(private val dir: File, private val dao: AssistantDao? = null) {
    private val file: File get() = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<NotifEntry> = dao?.let { d ->
        runBlocking { d.notifs(MAX) }.map(::toEntry)
    } ?: read().entries

    private fun toEntry(r: NotifRow) = NotifEntry(
        key = r.key, pkg = r.pkg, appLabel = r.appLabel, sender = r.sender,
        text = r.text, at = r.at, canReply = r.canReply, summary = r.summary,
    )

    private fun toRow(e: NotifEntry) = NotifRow(
        key = e.key, pkg = e.pkg, appLabel = e.appLabel, sender = e.sender,
        text = e.text, at = e.at, canReply = e.canReply, summary = e.summary,
    )

    /** Newest first; an update of the same key replaces the old row instead of duplicating it. */
    @Synchronized
    fun upsert(entry: NotifEntry) {
        if (dao != null) { runBlocking { dao.addNotif(toRow(entry), MAX) }; return }
        val doc = read()
        val kept = doc.entries.filterNot { it.key == entry.key }
        write(doc.copy(entries = (listOf(entry) + kept).sortedByDescending { it.at }.take(MAX)))
    }

    fun today(dayStart: Long): List<NotifEntry> = all().filter { it.at >= dayStart }

    fun since(from: Long): List<NotifEntry> = all().filter { it.at >= from }

    /** Distinct senders seen since [from], newest activity first. */
    fun sendersSince(from: Long): List<String> =
        since(from).filter { !it.summary && it.sender.isNotBlank() }
            .groupBy { it.sender }
            .mapValues { (_, v) -> v.maxOf { it.at } }
            .entries.sortedByDescending { it.value }
            .map { it.key }

    @Synchronized
    fun clear() {
        if (dao != null) { runBlocking { dao.clearNotifs() }; return }
        write(NotifDoc())
    }

    private fun read(): NotifDoc = runCatching {
        if (!file.exists()) NotifDoc() else json.decodeFromString(NotifDoc.serializer(), file.readText())
    }.getOrElse { NotifDoc() }

    private fun write(doc: NotifDoc) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(NotifDoc.serializer(), doc))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        const val FILE_NAME = "notifications.json"
        const val MAX = 400
    }
}
