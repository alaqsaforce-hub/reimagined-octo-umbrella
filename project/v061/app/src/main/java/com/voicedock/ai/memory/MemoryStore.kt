package com.voicedock.ai.memory

import com.voicedock.ai.data.database.AssistantDao
import com.voicedock.ai.data.database.MemoryRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * v0.7.0 — "এই কথা মনে রাখো" / "আমি কাল কী বলেছিলাম?".
 *
 * Deliberately a plain JSON file in the app's private directory rather than a new Room table: no
 * migration risk for an existing install, trivially unit-testable on the JVM, and small by design
 * ([MAX_ENTRIES]). If reminders/jobs need querying later, this moves to Room in the phase that needs it.
 */
@Serializable
data class MemoryEntry(
    val id: String,
    val text: String,
    val createdAt: Long,
    val source: String = "voice",
)

@Serializable
data class MemoryDoc(
    val version: Int = 1,
    val entries: List<MemoryEntry> = emptyList(),
)

/**
 * v0.10.0 — storage is Room when a [dao] is supplied (the app always does), with the original JSON file
 * kept as the import/test path ([dao] == null). Same behaviour either way: newest first, blank text
 * ignored, capped at [MAX_ENTRIES].
 */
class MemoryStore(private val dir: File, private val dao: AssistantDao? = null) {
    private val file: File get() = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun all(): List<MemoryEntry> = dao?.let { d ->
        runBlocking { d.memories(MAX_ENTRIES) }.map { MemoryEntry(it.id, it.text, it.createdAt, it.source) }
    } ?: read().entries

    /** Newest first, capped. Blank text is ignored. */
    @Synchronized
    fun add(text: String, now: Long = System.currentTimeMillis(), source: String = "voice"): MemoryEntry? {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isBlank()) return null
        val entry = MemoryEntry(UUID.randomUUID().toString(), clean, now, source)
        if (dao != null) {
            runBlocking { dao.addMemory(MemoryRow(entry.id, entry.text, entry.createdAt, entry.source), MAX_ENTRIES) }
            return entry
        }
        val doc = read()
        write(doc.copy(entries = (listOf(entry) + doc.entries).take(MAX_ENTRIES)))
        return entry
    }

    @Synchronized
    fun forget(id: String): Boolean {
        if (dao != null) {
            val before = runBlocking { dao.memoryCount() }
            runBlocking { dao.forgetMemory(id) }
            return runBlocking { dao.memoryCount() } < before
        }
        val doc = read()
        val kept = doc.entries.filterNot { it.id == id }
        if (kept.size == doc.entries.size) return false
        write(doc.copy(entries = kept))
        return true
    }

    @Synchronized
    fun clear() {
        if (dao != null) { runBlocking { dao.clearMemory() }; return }
        write(MemoryDoc())
    }

    fun search(query: String, limit: Int = 5): List<MemoryEntry> = MemoryText.search(all(), query, limit)

    private fun read(): MemoryDoc = runCatching {
        if (!file.exists()) MemoryDoc() else json.decodeFromString(MemoryDoc.serializer(), file.readText())
    }.getOrElse { MemoryDoc() }

    private fun write(doc: MemoryDoc) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(MemoryDoc.serializer(), doc))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        const val FILE_NAME = "voice_memory.json"
        const val MAX_ENTRIES = 300
        const val MAX_TEXT = 500
    }
}

/** Pure search + answer wording, so "কী মনে রাখতে বলেছিলাম" is testable without files. */
object MemoryText {

    private val stop = setOf(
        "আমি", "আমার", "আমাকে", "কি", "কী", "কথা", "মনে", "রাখতে", "বলেছিলাম", "বলেছিলে", "তুমি", "তোমার",
        "the", "a", "an", "what", "did", "i", "say", "tell", "you", "remember", "to", "me", "about", "was",
    )

    fun tokens(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .split(' ')
            .map { it.trim() }
            .filter { it.length > 1 && it !in stop }

    /**
     * Relevance search: an entry matches when it shares a token with the query. Ties are broken by
     * newest first (the caller passes entries newest-first).
     */
    fun search(entries: List<MemoryEntry>, query: String, limit: Int = 5): List<MemoryEntry> {
        val q = tokens(query)
        if (entries.isEmpty()) return emptyList()
        if (q.isEmpty()) return entries.take(limit)
        val scored = entries.mapNotNull { e ->
            val hay = tokens(e.text).toSet()
            val score = q.count { tok -> hay.any { it == tok || it.startsWith(tok) || tok.startsWith(it) } }
            if (score > 0) e to score else null
        }
        return scored.sortedWith(compareByDescending<Pair<MemoryEntry, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit).map { it.first }
    }

    /** The sentence shown/spoken after a recall command. Never invents anything. */
    fun recallAnswer(found: List<MemoryEntry>, total: Int, bengali: Boolean): String = when {
        found.isEmpty() && total == 0 ->
            if (bengali) "এখনো কিছু মনে রাখা হয়নি।" else "Nothing has been saved to memory yet."
        found.isEmpty() ->
            if (bengali) "এর সাথে মেলে এমন কিছু মনে রাখা নেই (মোট $total টি)।" else "Nothing matching that is in memory ($total saved in total)."
        found.size == 1 -> {
            val e = found.first()
            if (bengali) "মনে রাখা হয়েছে: ${e.text}" else "You asked me to remember: ${e.text}"
        }
        else -> buildString {
            append(if (bengali) "মেলে এমন ${found.size} টি:" else "${found.size} matches:")
            found.forEach { append("\n• ").append(it.text) }
        }
    }

    fun storedAnswer(text: String, bengali: Boolean): String =
        if (bengali) "মনে রাখলাম ✓ $text" else "Saved to memory ✓ $text"
}
