package com.voicedock.ai.command

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * v0.7.0 — a local ring buffer of the last [MAX] commands: what was said, what was understood, which
 * strategy ran and whether it worked. On-device only, never uploaded. This is what makes a failed
 * command diagnosable ("why didn't it open WhatsApp?") instead of a mystery.
 */
@Serializable
data class CommandLogEntry(
    val id: String,
    val at: Long,
    val raw: String,
    val intent: String,
    val confidence: Double,
    /** who produced the parse: "offline", "model", "choice" */
    val source: String,
    /** which executor route ran, or "none" */
    val strategy: String,
    val ok: Boolean,
    val detail: String = "",
)

@Serializable
data class CommandLogDoc(val version: Int = 1, val entries: List<CommandLogEntry> = emptyList())

class CommandLog(private val dir: File) {
    private val file: File get() = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<CommandLogEntry> = read().entries

    fun recent(n: Int = 20): List<CommandLogEntry> = all().take(n)

    @Synchronized
    fun record(
        raw: String,
        intent: Intent,
        confidence: Double,
        source: String,
        strategy: String,
        ok: Boolean,
        detail: String = "",
        now: Long = System.currentTimeMillis(),
    ): CommandLogEntry {
        val e = CommandLogEntry(UUID.randomUUID().toString(), now, raw.take(300), intent.id,
            confidence.coerceIn(0.0, 1.0), source, strategy, ok, detail.take(300))
        write(CommandLogDoc(entries = (listOf(e) + read().entries).take(MAX)))
        return e
    }

    @Synchronized
    fun clear() = write(CommandLogDoc())

    private fun read(): CommandLogDoc = runCatching {
        if (!file.exists()) CommandLogDoc() else json.decodeFromString(CommandLogDoc.serializer(), file.readText())
    }.getOrElse { CommandLogDoc() }

    private fun write(doc: CommandLogDoc) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(CommandLogDoc.serializer(), doc))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object {
        const val FILE_NAME = "command_log.json"
        const val MAX = 50
    }
}
