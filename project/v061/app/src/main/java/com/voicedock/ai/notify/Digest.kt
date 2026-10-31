package com.voicedock.ai.notify

/** One sender's activity today. */
data class SenderGroup(
    val sender: String,
    val appLabel: String,
    val pkg: String,
    val count: Int,
    val lastAt: Long,
    val lastText: String,
    val canReply: Boolean,
)

/**
 * v0.8.0 — "আজ কে কে মেসেজ দিয়েছে?" answered from notifications only.
 *
 * The rule this file exists to enforce: **never invent message content.** If WhatsApp (or any app) hides
 * previews, the digest says who wrote and when, and states plainly that the text is hidden — it does not
 * guess, and it does not ask a cloud model to guess either.
 *
 * Pure Kotlin: grouping, ordering, wording, and the summary prompt are all unit-tested.
 */
object Digest {

    /** Group by sender for everything at/after [dayStart], newest activity first. */
    fun group(entries: List<NotifEntry>, dayStart: Long): List<SenderGroup> {
        val real = entries.filter { it.at >= dayStart && !it.summary && it.sender.isNotBlank() }
        return real.groupBy { it.sender }
            .map { (sender, list) ->
                val last = list.maxByOrNull { it.at } ?: list.first()
                SenderGroup(
                    sender = sender,
                    appLabel = last.appLabel,
                    pkg = last.pkg,
                    count = list.size,
                    lastAt = last.at,
                    lastText = list.filter { it.hasText }.maxByOrNull { it.at }?.text.orEmpty(),
                    canReply = list.any { it.canReply },
                )
            }
            .sortedByDescending { it.lastAt }
    }

    /** True when *some* sender's text is readable — decides whether "preview বন্ধ" applies at all. */
    fun anyPreview(entries: List<NotifEntry>, dayStart: Long): Boolean =
        entries.any { it.at >= dayStart && !it.summary && it.hasText }

    /**
     * The text shown in the bubble (and spoken). Deterministic, offline, no model involved.
     */
    fun summary(entries: List<NotifEntry>, dayStart: Long, bengali: Boolean, maxSenders: Int = 6): String {
        val groups = group(entries, dayStart)
        fun bn(b: String, e: String) = if (bengali) b else e
        if (groups.isEmpty()) {
            return if (countAll(entries, dayStart) == 0) {
                bn("আজ এখনো কোনো মেসেজ আসেনি (নোটিফিকেশন থেকে)।", "No messages today so far (from notifications).")
            } else {
                bn("আজ নোটিফিকেশন এসেছে, কিন্তু কোনো প্রেরকের নাম পাওয়া যায়নি।", "Notifications arrived today, but no sender name was available.")
            }
        }
        val shown = groups.take(maxSenders)
        val sb = StringBuilder()
        sb.append(bn("আজ ${groups.size} জনের কাছ থেকে:", "Today, from ${groups.size} people:"))
        for (g in shown) {
            sb.append('\n').append("• ").append(g.sender)
            if (g.count > 1) sb.append(" (").append(g.count).append(bn("টি", "")).append(if (g.count > 1 && !bengali) " msgs" else "").append(")")
            if (g.lastText.isNotBlank()) sb.append(": ").append(g.lastText.take(90))
            else sb.append(bn(" — লেখা দেখা যাচ্ছে না (preview বন্ধ)", " — text hidden (preview off)"))
        }
        if (groups.size > shown.size) sb.append('\n').append(bn("…আরও ${groups.size - shown.size} জন।", "…and ${groups.size - shown.size} more."))
        if (!anyPreview(entries, dayStart)) {
            sb.append('\n').append(
                bn("কোনো মেসেজের লেখা দেখা যাচ্ছে না — হোয়াটসঅ্যাপ/অ্যাপের নোটিফিকেশন প্রিভিউ বন্ধ আছে।",
                    "No message text is visible — notification previews are switched off for those apps."),
            )
        }
        return sb.toString()
    }

    private fun countAll(entries: List<NotifEntry>, dayStart: Long) = entries.count { it.at >= dayStart }

    /** Last [n] messages with readable text, newest first — used by "শেষ ৫টা মেসেজ পড়ো". */
    fun lastMessages(entries: List<NotifEntry>, n: Int = 5): List<NotifEntry> =
        entries.filter { !it.summary && it.hasText }.sortedByDescending { it.at }.take(n)

    fun readAloud(entries: List<NotifEntry>, n: Int, bengali: Boolean): String {
        val msgs = lastMessages(entries, n)
        if (msgs.isEmpty()) {
            return if (bengali) "পড়ার মতো মেসেজ নেই (হয় আসেনি, নয় লেখা লুকানো)।"
            else "There is nothing to read (nothing arrived, or the text is hidden)."
        }
        return msgs.reversed().joinToString("\n") { m ->
            if (bengali) "${m.sender}: ${m.text}" else "${m.sender}: ${m.text}"
        }
    }

    /**
     * Prompt for an AI *summary* — deliberately carries only what the digest already shows.
     * Not used unless the user switches on cloud summarisation (default off).
     */
    fun aiPrompt(groups: List<SenderGroup>, bengali: Boolean): String = buildString {
        append("Summarise these message notifications in 3-5 short lines. Do not invent anything that is not listed.\n")
        append("If a sender has no text, say the text is hidden; never guess its content.\n")
        for (g in groups) {
            append("- ").append(g.sender).append(" (").append(g.count).append(" msg): ")
            append(g.lastText.ifBlank { "[text hidden]" }).append('\n')
        }
        append(if (bengali) "উত্তর বাংলায় দাও।" else "Answer in English.")
    }
}
