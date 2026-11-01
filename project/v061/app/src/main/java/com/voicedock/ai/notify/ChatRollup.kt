package com.voicedock.ai.notify

/**
 * v1.3.2 — "রহিমের সাথে কী কথা হলো?" — one chat's messages, rolled up from the notification log.
 *
 * This is the per-conversation companion to [Digest]'s per-day summary. The rule is unchanged and is the whole
 * point of the file: **the phone only knows what the app put in its notification.** When WhatsApp hides the
 * preview, the roll-up says who wrote and how many times, and states plainly that the text is hidden — it never
 * fills the gap, and never asks a model to fill it either.
 */
data class ChatThread(
    val sender: String,
    val appLabel: String,
    val pkg: String,
    val count: Int,
    val lastAt: Long,
    /** Oldest → newest, text only, at most [ChatRollup.MAX_KEPT]. */
    val messages: List<String>,
    /** True when at least one message arrived with no readable text. */
    val hiddenPreviews: Boolean,
    val canReply: Boolean,
)

object ChatRollup {

    /** How many messages of a thread are kept for the roll-up (a notification log is not a chat backup). */
    const val MAX_KEPT = 8

    /** Every conversation that has had activity at/after [since], newest activity first. */
    fun threads(entries: List<NotifEntry>, since: Long): List<ChatThread> = entries
        .filter { it.at >= since && !it.summary && it.sender.isNotBlank() }
        .groupBy { it.sender.trim().lowercase() to it.pkg }
        .map { (_, list) ->
            val byTime = list.sortedBy { it.at }
            val last = byTime.last()
            ChatThread(
                sender = last.sender.trim(),
                appLabel = last.appLabel,
                pkg = last.pkg,
                count = list.size,
                lastAt = last.at,
                messages = byTime.filter { it.hasText }.map { it.text.trim() }.takeLast(MAX_KEPT),
                hiddenPreviews = list.any { !it.hasText },
                canReply = list.any { it.canReply },
            )
        }
        .sortedByDescending { it.lastAt }

    /**
     * The thread the user asked about. A name is matched case-insensitively on word boundaries when possible,
     * then as a substring — the same forgiving order the contact matcher uses, so "রহিম" finds "রহিম উদ্দিন".
     */
    fun find(entries: List<NotifEntry>, since: Long, query: String): ChatThread? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = threads(entries, since)
        all.firstOrNull { it.sender.lowercase() == q }?.let { return it }
        all.firstOrNull { it.sender.lowercase().split(' ', '.', '-', '_').contains(q) }?.let { return it }
        return all.firstOrNull { it.sender.lowercase().contains(q) }
    }

    /** The answer shown in the bubble: deterministic, offline, no model. */
    fun text(thread: ChatThread, bengali: Boolean, maxMessages: Int = 4): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val head = bn(
            "${thread.sender} — ${bnNum(thread.count)}টা মেসেজ (${thread.appLabel})",
            "${thread.sender} — ${thread.count} message(s) (${thread.appLabel})",
        )
        if (thread.messages.isEmpty()) {
            return head + "\n" + bn(
                "লেখা দেখা যাচ্ছে না — অ্যাপটি নোটিফিকেশনে মেসেজের লেখা দেখায়নি, তাই সারমর্ম বানানো হয়নি।",
                "The text is not visible — the app did not put it in the notification, so no summary was invented.",
            )
        }
        val tail = thread.messages.takeLast(maxMessages)
        val body = tail.joinToString("\n") { "• $it" }
        val more = if (thread.count > tail.size) {
            "\n" + bn("(আরও ${bnNum(thread.count - tail.size)}টা — মোট ${bnNum(thread.count)})",
                "(and ${thread.count - tail.size} more — ${thread.count} in all)")
        } else ""
        val hidden = if (thread.hiddenPreviews) {
            "\n" + bn("(কয়েকটা নোটিফিকেশনে লেখা ছিল না)", "(some notifications had no text)")
        } else ""
        val canReply = if (thread.canReply) {
            "\n" + bn("উত্তর দিতে বলুন: “রহিমকে বলো …”", "To reply say: “tell Rahim …”")
        } else ""
        return head + "\n" + body + more + hidden + canReply
    }

    /** Prompt for the AI path (used when the user asks for a real summary of a long thread). */
    fun aiPrompt(thread: ChatThread, bengali: Boolean): String = if (bengali) {
        "নিচে ‘${thread.sender}’-এর সাথে যেসব মেসেজ নোটিফিকেশন থেকে পাওয়া গেছে তা দেওয়া হলো। " +
            "সংক্ষেপে ৩–৪ বাক্যে সারমর্ম লেখো, আর শেষে বলো কেউ কিছু চাইলে কী উত্তর দিতে হবে। " +
            "যা লেখা নেই তা বানিয়ে বলো না।"
    } else {
        "Below are the messages from ‘${thread.sender}’ that arrived as notifications. Summarise them in 3–4 " +
            "sentences and end with what (if anything) needs a reply. Never invent text that is not shown."
    }

    fun aiMessages(thread: ChatThread): String =
        thread.messages.joinToString("\n") { "- $it" }

    fun notFound(query: String, bengali: Boolean): String = if (bengali) {
        "‘$query’-এর কোনো মেসেজ আজকের নোটিফিকেশনে পাওয়া যায়নি। নোটিফিকেশন অ্যাক্সেস চালু আছে কি দেখুন, " +
            "আর অ্যাপটি মেসেজের লেখা দেখায় কি না তাও।"
    } else {
        "No messages from ‘$query’ were found in today's notifications. Check that notification access is on, " +
            "and that the app shows message text in its notifications."
    }

    fun empty(bengali: Boolean): String = if (bengali) {
        "আজ কোনো চ্যাটের মেসেজ নোটিফিকেশনে আসেনি।"
    } else {
        "No chat messages arrived in notifications today."
    }

    /** Bengali numerals without pulling a UI dependency into this package. */
    private fun bnNum(v: Int): String = buildString {
        for (c in v.toString()) if (c in '0'..'9') append("০১২৩৪৫৬৭৮৯"[c - '0']) else append(c)
    }
}
