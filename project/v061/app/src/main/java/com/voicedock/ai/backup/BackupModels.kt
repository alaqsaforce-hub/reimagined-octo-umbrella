package com.voicedock.ai.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * v0.7.0 — portable backup of the user's own text.
 *
 * Design rules (they are enforced by the model, not by a filter):
 *  1. **Secrets cannot be exported.** There is no field for API keys, IMAP app passwords, GitHub
 *     tokens, OAuth tokens, the ChatGPT/OpenRouter session or the Keystore blob. Exporting them would
 *     require changing this file, which is why the guarantee holds.
 *  2. **Everything is plain JSON** the user can read and diff. No proprietary container.
 *  3. **Import never destroys.** It merges: a draft is only replaced when the same draft id is
 *     already present, and a knowledge document is skipped when its content is already stored.
 *  4. **Bounded.** Huge or hostile files are rejected or trimmed instead of filling the phone.
 *
 * This file is Android-free on purpose so it runs in a plain JVM unit test.
 */

private const val VOICEDOCK_APP_ID = "com.voicedock.ai"
private const val BACKUP_SCHEMA = 1

@Serializable
data class BackupDraft(
    val id: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val rawText: String = "",
    val formattedText: String = "",
    /** Opaque segment list from the dictation engine; kept so a restored draft merges the same way. */
    val segmentsJson: String = "",
    val status: String = "SAVED",
    val language: String = "",
    val engine: String = "",
    val destination: String = "",
)

@Serializable
data class BackupDocument(
    val title: String = "",
    val source: String = "NOTE",
    /** e.g. file name or Gmail message id; kept for the user's own bookkeeping. */
    val sourceRef: String = "",
    val mime: String = "text/plain",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** SHA-256 of the normalised text; used to skip documents that are already stored. */
    val contentHash: String = "",
    /** Chunks exactly as indexed (they overlap by design, so they are stored verbatim, never rejoined). */
    val chunks: List<String> = emptyList(),
)

@Serializable
data class BackupFile(
    val app: String = VOICEDOCK_APP_ID,
    val schema: Int = BACKUP_SCHEMA,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    val drafts: List<BackupDraft> = emptyList(),
    val documents: List<BackupDocument> = emptyList(),
) {
    val totalChars: Int
        get() = drafts.sumOf { it.rawText.length + it.formattedText.length } +
            documents.sumOf { d -> d.chunks.sumOf { it.length } }
}

object BackupCodec {
    const val APP_ID = VOICEDOCK_APP_ID
    /** Bump when the JSON shape changes incompatibly; older files are rejected with a clear message. */
    const val SCHEMA = BACKUP_SCHEMA
    const val SUPPORTED_MIN_SCHEMA = 1

    const val MAX_JSON_CHARS = 24_000_000
    const val MAX_DRAFTS = 5_000
    const val MAX_DOCUMENTS = 5_000
    const val MAX_CHUNKS_PER_DOCUMENT = 5_000
    const val MAX_TITLE = 200
    const val MAX_TEXT_PER_FIELD = 200_000
    const val MAX_CHUNK = 4_000
    const val MAX_SHORT_FIELD = 500

    private val HASH = Regex("^[0-9a-f]{64}$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /**
     * Parses and validates a backup file. Unknown extra keys are ignored so a newer app version can
     * still be read by an older one; a newer *schema* is refused instead of silently half-imported.
     */
    fun decode(raw: String): BackupDecode {
        if (raw.isBlank()) return BackupDecode.Error("The file is empty.")
        if (raw.length > MAX_JSON_CHARS) {
            return BackupDecode.Error("The file is too large (%.1f MB, limit %d MB)."
                .format(raw.length / 1_000_000.0, MAX_JSON_CHARS / 1_000_000))
        }
        val parsed = runCatching { json.decodeFromString(BackupFile.serializer(), raw) }.getOrNull()
            ?: return BackupDecode.Error("This is not a VoiceDock backup file (invalid JSON).")
        if (parsed.app != APP_ID) return BackupDecode.Error("This backup belongs to another app (${parsed.app}).")
        if (parsed.schema < SUPPORTED_MIN_SCHEMA || parsed.schema > SCHEMA) {
            return BackupDecode.Error("Unsupported backup version ${parsed.schema} (this app reads version $SCHEMA).")
        }

        val notes = ArrayList<String>(3)
        var dropped = 0

        val drafts = ArrayList<BackupDraft>()
        val seenDraftIds = HashSet<String>()
        for (d in parsed.drafts) {
            if (drafts.size >= MAX_DRAFTS) { notes += "Only the first $MAX_DRAFTS drafts were imported."; break }
            val id = d.id.trim().take(64)
            if (id.isEmpty() || !seenDraftIds.add(id)) { dropped++; continue }
            val raw = d.rawText.take(MAX_TEXT_PER_FIELD)
            val formatted = d.formattedText.take(MAX_TEXT_PER_FIELD)
            if (raw.isBlank() && formatted.isBlank()) { dropped++; continue }
            drafts += d.copy(
                id = id,
                rawText = raw,
                formattedText = formatted,
                segmentsJson = d.segmentsJson.take(MAX_TEXT_PER_FIELD),
                status = sanitizeStatus(d.status),
                language = d.language.trim().take(40),
                engine = d.engine.trim().take(40),
                destination = d.destination.trim().take(MAX_SHORT_FIELD),
            )
        }

        val documents = ArrayList<BackupDocument>()
        val seenHashes = HashSet<String>()
        for (doc in parsed.documents) {
            if (documents.size >= MAX_DOCUMENTS) { notes += "Only the first $MAX_DOCUMENTS documents were imported."; break }
            val chunks = doc.chunks.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_CHUNKS_PER_DOCUMENT)
                .map { it.take(MAX_CHUNK) }
                .toList()
            if (chunks.isEmpty()) { dropped++; continue }
            val hash = if (HASH.matches(doc.contentHash)) doc.contentHash else hashOfChunks(chunks)
            if (!seenHashes.add(hash)) { dropped++; continue }
            documents += doc.copy(
                title = doc.title.trim().take(MAX_TITLE).ifBlank { chunks.first().take(60) },
                source = sanitizeSource(doc.source),
                sourceRef = doc.sourceRef.trim().take(MAX_SHORT_FIELD),
                mime = doc.mime.trim().take(120).ifBlank { "text/plain" },
                contentHash = hash,
                chunks = chunks,
            )
        }

        if (dropped > 0) notes += "$dropped empty or duplicate entr${if (dropped == 1) "y" else "ies"} were skipped."

        return BackupDecode.Ok(
            parsed.copy(drafts = drafts, documents = documents),
            notes,
        )
    }

    fun sanitizeStatus(value: String): String {
        val v = value.trim().uppercase()
        return if (v in DraftStatuses) v else "SAVED"
    }

    fun sanitizeSource(value: String): String {
        val v = value.trim().uppercase()
        return if (v in Sources) v else "NOTE"
    }

    /** Deterministic stand-in hash when a file has no (or a malformed) content hash. */
    fun hashOfChunks(chunks: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0x20) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Draft statuses that may appear in a backup (kept in sync with `DraftStatus`). */
    val DraftStatuses = setOf("ACTIVE", "REVIEW", "INSERTED", "SAVED", "CANCELLED", "CLEARED")

    /** Knowledge sources that may appear in a backup (kept in sync with `KbSource`). */
    val Sources = setOf("NOTE", "SHARE", "PROCESS_TEXT", "FILE", "DRIVE", "GMAIL", "GITHUB", "DRAFT")
}

sealed interface BackupDecode {
    data class Ok(val file: BackupFile, val notes: List<String>) : BackupDecode
    data class Error(val reason: String) : BackupDecode
}
