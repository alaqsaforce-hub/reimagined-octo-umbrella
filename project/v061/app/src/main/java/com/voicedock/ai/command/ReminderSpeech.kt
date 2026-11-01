package com.voicedock.ai.command

import com.voicedock.ai.schedule.TimeWords

/**
 * v1.2.0 — "৩টায় মনে করিয়ে দাও পানি খেতে" → intent + goal + time, without a model.
 *
 * The time itself is not parsed here ([TimeWords] owns that grammar); this object only splits the sentence
 * into *what* and *when*, because Bengali puts them in either order and the word order changes the meaning
 * of a naive "everything after the verb" rule.
 */
object ReminderSpeech {

    private val markers = listOf(
        "মনে করিয়ে দাও", "মনে করিয়ে দিও", "মনে করিয়ে দিতে", "মনে করিয়ে দেবার", "মনে করিয়ে দেয়",
        "মনে করিয়ে দেও", "রিমাইন্ডার দাও", "রিমাইন্ডার দিও", "রিমাইন্ডার কর", "রিমাইন্ডার সেট",
        "remind me to", "remind me", "set a reminder", "set reminder", "reminder to", "reminder",
    )
    private val listMarkers = listOf(
        "রিমাইন্ডার দেখাও", "রিমাইন্ডার তালিকা", "রিমাইন্ডারের তালিকা", "রিমাইন্ডারগুলো", "রিমাইন্ডারেরা",
        "রিমাইন্ডার গুলো", "রিমাইন্ডারের", "কী কী রিমাইন্ডার",
        "কি কি রিমাইন্ডার", "my reminders", "list reminders", "show reminders", "list my reminders",
    )

    private val timeChips = Regex(
        "(\\d{1,2}\\s*[:.]\\s*\\d{2})|(\\d{1,3}\\s*(?:মিনিট|ঘণ্টা|ঘন্টা|সেকেন্ড|minutes?|mins?|hours?|hrs?|seconds?|secs?))|" +
            "(\\d{1,2}\\s*(?:টা|টায়|টার|টায়|baje|o'?clock))|" +
            "(আজ|আজকে|কাল|কালকে|আগামীকাল|আগামী\\s+কাল|পরশু|ভোর|সকাল|সকালে|দুপুর|দুপুরে|বিকাল|বিকালে|বিকেল|বিকেলে|সন্ধ্যা|সন্ধ্যায়|রাত|রাতে)|" +
            "(in\\s+\\d+\\s+(?:minutes?|mins?|hours?))|(\\bpm\\b|\\bam\\b|tomorrow|today|tonight)",
        RegexOption.IGNORE_CASE,
    )

    private val filler = Regex("(?i)^(আমাকে|আমায়|যে|একটু|দয়া করে|please|to|that)\\s+")
    private val trailing = Regex("(?i)\\s+(বলো|বলুন|please|ok)\\s*$")

    fun looksLike(text: String): Boolean {
        val l = text.lowercase()
        return markers.any { l.contains(it) } || listMarkers.any { l.contains(it) }
    }

    fun isListRequest(text: String): Boolean {
        val l = text.lowercase().trim()
        if (listMarkers.any { l.contains(it) }) return true
        // The bare noun means "show me what I have"; "মনে করিয়ে দাও" alone keeps asking for the goal.
        return l == "রিমাইন্ডার" || l == "reminders" || l == "reminder"
    }

    /** The part after the verb (or before it, when the goal came first) with the time words cut out. */
    fun goal(text: String): String {
        val l = text.lowercase()
        val marker = markers.maxByOrNull { if (l.contains(it)) it.length else 0 }?.takeIf { l.contains(it) }
        var body = if (marker != null) {
            val after = text.substring(l.indexOf(marker) + marker.length)
            val trimmed = after.trim().trim('।', ',', '.', ' ')
            // "মনে করিয়ে দাও" said alone → the goal may have come first ("পানি খেতে ৩টায় মনে করিয়ে দাও").
            if (trimmed.isNotBlank() && TimeWords.parse(trimmed, 0L) != null && timeChips.replace(trimmed, "").isBlank()) {
                text.substring(0, l.indexOf(marker))
            } else if (trimmed.isNotBlank()) after else text.substring(0, l.indexOf(marker))
        } else text
        // The chips are matched on ASCII digits (Bengali ৩ is not \d) but removed from the ORIGINAL string by
        // span, so the goal keeps its own script and digits.
        val ascii = TimeWords.asciiDigits(body)
        val spans = timeChips.findAll(ascii).map { it.range }.toList()
        if (spans.isNotEmpty()) {
            val sb = StringBuilder()
            var last = 0
            for (r in spans) {
                if (r.first > last) sb.append(body, last, r.first)
                sb.append(' ')
                last = r.last + 1
            }
            if (last < body.length) sb.append(body, last, body.length)
            body = sb.toString()
        }
        body = body.replace(Regex("\\s+"), " ").trim()
        body = body.replace(filler, "").replace(trailing, "").trim()
        // A left-over particle is not a goal ("৩টায় মনে করিয়ে দাও" must ask for the goal, not store "য়").
        val particles = setOf("য়", "টা", "টায়", "টার", "পরে", "পর", "baje", "o'clock", "at", "in")
        body = body.split(" ").filter { it.isNotBlank() && it.lowercase() !in particles }.joinToString(" ")
        return body.trim('।', ',', '.', ' ', '-', ':')
    }
}
