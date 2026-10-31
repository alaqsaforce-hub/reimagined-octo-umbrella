package com.voicedock.ai.command

/**
 * v0.10.0 — one honest step back.
 *
 * Only actions that can genuinely be reversed are offered: a memory that was saved (delete the row), a
 * media command (send the inverse transport command). Placing a call, sending a message or launching an
 * app cannot be undone, so no undo button appears for them — a button that does nothing would be worse
 * than no button.
 *
 * The log keeps a single entry with a short lifetime: "undo" always means "the thing I just did", never
 * something from ten minutes ago.
 */
enum class UndoKind { MEMORY_ADD, MEDIA, OTHER }

data class UndoEntry(
    val id: String,
    /** What will be reverted, in the user's language, e.g. "মনে রাখা: কাল ৩টায় মিটিং". */
    val label: String,
    val at: Long,
    val kind: UndoKind,
    /** For [UndoKind.MEDIA]: the action that was performed, so the inverse can be sent. */
    val mediaActionName: String = "",
)

/** Pure bookkeeping — the caller owns the actual revert. */
class UndoLog(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var last: UndoEntry? = null

    fun push(entry: UndoEntry) { last = entry }

    /** The entry if it is still fresh; expired entries are dropped. */
    fun peek(): UndoEntry? {
        val e = last ?: return null
        if (clock() - e.at > ttlMs) { last = null; return null }
        return e
    }

    /** Consumes the entry — an undo can only happen once. */
    fun take(): UndoEntry? = peek().also { if (it != null) last = null }

    fun clear() { last = null }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
    }
}

/** Wording for the undo chip and its confirmation. Pure, so both languages are unit-tested. */
object UndoText {

    fun chip(bengali: Boolean): String = if (bengali) "↩ ফিরিয়ে নাও" else "↩ Undo"

    fun done(entry: UndoEntry, bengali: Boolean): String =
        if (bengali) "ফিরিয়ে নেওয়া হলো — ${entry.label}" else "Undone — ${entry.label}"

    fun nothing(bengali: Boolean): String =
        if (bengali) "ফিরিয়ে নেওয়ার মতো কিছু নেই (৬০ সেকেন্ডের মধ্যে বলতে হয়)।"
        else "Nothing to undo (it has to be within 60 seconds)."
}
