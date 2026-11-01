package com.voicedock.ai.summaries

/**
 * v1.3.3 — the accessibility pass, part two: reading the **transcript panel** of the YouTube video that is open
 * on screen.
 *
 * v1.3.2 could only read a video's title and description, and said so. With the accessibility service doing the
 * reading, the captions that the YouTube app already renders become available — but only when the user has opened
 * the panel, because nothing here taps around inside an app on its own. Everything else about this file is the
 * same discipline as the rest of the project: clean the text, keep the user's language, and if the panel is not
 * there, say exactly how to open it instead of summarising something else.
 */
data class Transcript(
    val lines: List<String>,
    /** True when the screen looked like a transcript panel (timestamps / panel labels / many subtitle lines). */
    val confident: Boolean,
)

object ScreenTranscript {

    const val MAX_LINES = 60
    const val MAX_CHARS = 6_000

    /** Chrome that the panel and the video page put around the captions. */
    private val uiNoise = listOf(
        "show transcript", "hide transcript", "transcript", "search transcript", "auto-scroll", "toggle timestamps",
        "ট্রান্সক্রিপ্ট দেখাও", "ট্রান্সক্রিপ্ট", "সাবটাইটেল", "মন্তব্য", "comments", "subscribe", "subscribed",
        "like", "dislike", "share", "save", "download", "play next", "autoplay", "shorts", "library", "home",
    )

    private val timeLine = Regex("""^\s*(\d{1,2}:)?\d{1,2}:\d{2}\s*""")
    private val timeOnly = Regex("""^\s*((\d{1,2}:)?\d{1,2}:\d{2})\s*$""")

    /** Timestamps are the strongest signal that this really is the transcript panel. */
    fun timestampLines(raw: String): Int = raw.lineSequence().count { timeOnly.matches(it) }

    fun looksLikeTranscript(raw: String): Boolean {
        val low = raw.lowercase()
        if (low.contains("transcript") || low.contains("ট্রান্সক্রিপ্ট")) return true
        return timestampLines(raw) >= 5
    }

    fun clean(raw: String): Transcript {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        var chars = 0
        for (line in raw.lineSequence()) {
            var t = line.trim()
            if (t.isEmpty()) continue
            t = timeLine.replace(t, "").trim()
            if (t.length < 8) continue
            val low = t.lowercase()
            if (uiNoise.any { low == it || (low.startsWith(it) && t.length < it.length + 12) }) continue
            if (t.count { it.isDigit() } > t.length / 3 && t.length < 24) continue // durations, view counts
            val key = low
            if (!seen.add(key)) continue
            out += t
            chars += t.length
            if (out.size >= MAX_LINES || chars >= MAX_CHARS) break
        }
        return Transcript(out, confident = looksLikeTranscript(raw) && out.isNotEmpty())
    }

    /** What to show when the panel is not on screen — instructions, not an invented summary. */
    fun missingPanel(bengali: Boolean): String = if (bengali) {
        "স্ক্রিনে ট্রান্সক্রিপ্ট পাওয়া যায়নি। ইউটিউব অ্যাপে ভিডিওটা খুলে নিচের ‘…’ চেপে **Show transcript** " +
            "(বাংলায় ‘ট্রান্সক্রিপ্ট দেখাও’) চালু করুন, তারপর আবার বলুন — তখন আসল ক্যাপশন থেকেই সারমর্ম দেব।"
    } else {
        "No transcript is visible on screen. Open the video in the YouTube app, tap ‘…’ and turn on " +
            "**Show transcript**, then ask again — the summary will then come from the real captions."
    }

    fun emptyTranscript(bengali: Boolean): String = if (bengali) {
        "ট্রান্সক্রিপ্ট প্যানেল খোলা, কিন্তু তাতে পড়ার মতো লেখা নেই (এই ভিডিওতে ক্যাপশন নেই)।"
    } else {
        "The transcript panel is open but has nothing readable in it (this video has no captions)."
    }

    /** The answer when the AI path is used: always says the captions were read from the screen. */
    fun text(tr: Transcript, bengali: Boolean, summary: String?): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val head = bn("📝 স্ক্রিনের ট্রান্সক্রিপ্ট থেকে", "📝 From the on-screen transcript")
        val body = when {
            !summary.isNullOrBlank() -> summary.trim()
            else -> tr.lines.take(12).joinToString("\n") { "• $it" }
        }
        val tail = if (tr.lines.size > 12) {
            "\n" + bn("(মোট ${tr.lines.size} লাইন পড়া হয়েছে — উপরের অংশটুকু)", "(read ${tr.lines.size} lines — the start is shown)")
        } else ""
        return head + "\n" + body + tail
    }

    fun aiPrompt(bengali: Boolean): String = if (bengali) {
        "নিচে একটি ইউটিউব ভিডিওর ট্রান্সক্রিপ্ট (স্ক্রিন থেকে পড়া) দেওয়া হলো। ৪–৫ বাক্যে মূল কথা বলো, " +
            "তারপর ২–৩টা মূল পয়েন্ট বুলেটে। ট্রান্সক্রিপ্টে যা নেই তা বানিয়ে বলো না।"
    } else {
        "Below is a YouTube video's transcript, read from the screen. Give the gist in 4–5 sentences, then 2–3 " +
            "bullet key points. Never add anything that is not in the transcript."
    }
}
