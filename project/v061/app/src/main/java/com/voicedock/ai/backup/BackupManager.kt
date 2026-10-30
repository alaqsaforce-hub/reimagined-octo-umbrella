package com.voicedock.ai.backup

import android.content.Context
import com.voicedock.ai.BuildConfig
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import com.voicedock.ai.data.database.KbChunk
import com.voicedock.ai.data.database.KbDocument
import com.voicedock.ai.data.database.KbSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** What an import actually did (shown to the user; no invented numbers). */
data class ImportSummary(
    val draftsAdded: Int = 0,
    val draftsReplaced: Int = 0,
    val documentsAdded: Int = 0,
    val documentsSkipped: Int = 0,
    val notes: List<String> = emptyList(),
) {
    val changedAnything: Boolean get() = draftsAdded + draftsReplaced + documentsAdded > 0

    fun describe(bn: Boolean): String = if (bn)
        "খসড়া যোগ: $draftsAdded · প্রতিস্থাপিত: $draftsReplaced\n" +
            "নলেজ ডকুমেন্ট যোগ: $documentsAdded · বাদ (আগেই আছে): $documentsSkipped"
    else
        "Drafts added: $draftsAdded · replaced: $draftsReplaced\n" +
            "Knowledge documents added: $documentsAdded · skipped (already present): $documentsSkipped"
}

/**
 * Reads and writes the backup JSON for the local database.
 *
 * Export contains the user's drafts (raw + formatted text) and knowledge documents (title, source
 * metadata, and the indexed chunks). It contains **no** API keys, app passwords, tokens or OAuth
 * sessions — those live in `SecretStore` and are not reachable from here by design.
 *
 * Import merges. Nothing is deleted:
 *  - a draft with an id that is already stored is replaced by the file's version (same draft),
 *  - a knowledge document whose content hash is already stored is skipped.
 */
object BackupManager {

    suspend fun exportJson(context: Context): String = withContext(Dispatchers.IO) {
        val db = VoiceDock.db(context)
        val drafts = db.drafts().all().map { it.toBackup() }
        val documents = db.knowledge().allDocuments().map { doc ->
            doc.toBackup(chunks = db.knowledge().chunksOf(doc.id))
        }
        BackupCodec.encode(
            BackupFile(
                appVersion = BuildConfig.VERSION_NAME,
                exportedAt = System.currentTimeMillis(),
                drafts = drafts,
                documents = documents,
            )
        )
    }

    suspend fun importJson(context: Context, raw: String): ImportSummary = withContext(Dispatchers.IO) {
        when (val decoded = BackupCodec.decode(raw)) {
            is BackupDecode.Error -> ImportSummary(notes = listOf(decoded.reason))
            is BackupDecode.Ok -> {
                val db = VoiceDock.db(context)
                var added = 0
                var replaced = 0
                for (d in decoded.file.drafts) {
                    val existing = db.drafts().all().any { it.id == d.id }
                    db.drafts().upsert(d.toEntity())
                    if (existing) replaced++ else added++
                }
                var docsAdded = 0
                var docsSkipped = 0
                for (doc in decoded.file.documents) {
                    if (db.knowledge().byHash(doc.contentHash) != null) { docsSkipped++; continue }
                    val now = System.currentTimeMillis()
                    val entity = KbDocument(
                        id = UUID.randomUUID().toString(),
                        title = doc.title,
                        source = runCatching { KbSource.valueOf(doc.source) }.getOrDefault(KbSource.NOTE),
                        sourceRef = doc.sourceRef,
                        mime = doc.mime,
                        createdAt = if (doc.createdAt > 0) doc.createdAt else now,
                        updatedAt = if (doc.updatedAt > 0) doc.updatedAt else now,
                        charCount = doc.chunks.sumOf { it.length },
                        contentHash = doc.contentHash,
                    )
                    db.knowledge().addDocument(
                        entity,
                        doc.chunks.mapIndexed { i, text -> KbChunk(docId = entity.id, ord = i, text = text) },
                    )
                    docsAdded++
                }
                ImportSummary(
                    draftsAdded = added,
                    draftsReplaced = replaced,
                    documentsAdded = docsAdded,
                    documentsSkipped = docsSkipped,
                    notes = decoded.notes,
                )
            }
        }
    }
}

private fun DictationDraft.toBackup() = BackupDraft(
    id = id,
    createdAt = createdAt,
    updatedAt = updatedAt,
    rawText = rawText,
    formattedText = formattedText,
    segmentsJson = segmentsJson,
    status = status.name,
    language = language,
    engine = engine,
    destination = destination,
)

private fun BackupDraft.toEntity() = DictationDraft(
    id = id,
    createdAt = createdAt,
    updatedAt = updatedAt,
    rawText = rawText,
    formattedText = formattedText,
    segmentsJson = segmentsJson,
    status = runCatching { DraftStatus.valueOf(BackupCodec.sanitizeStatus(status)) }.getOrDefault(DraftStatus.SAVED),
    language = language,
    engine = engine,
    destination = destination,
)

private fun KbDocument.toBackup(chunks: List<String>) = BackupDocument(
    title = title,
    source = source.name,
    sourceRef = sourceRef,
    mime = mime,
    createdAt = createdAt,
    updatedAt = updatedAt,
    contentHash = contentHash,
    chunks = chunks,
)
