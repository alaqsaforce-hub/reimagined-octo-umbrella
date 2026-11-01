package com.voicedock.ai.summaries

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * v1.3.2 — "এই ইউটিউব লিংকটার সারমর্ম বলো".
 *
 * **What this can honestly do:** read the video page and return its title, channel, length and description —
 * and, when an AI provider is configured, summarise that description.
 *
 * **What it cannot do:** read the captions. Captions live behind a signed transcript endpoint and, on the phone,
 * behind the YouTube app's transcript panel (which needs the accessibility pass). So the answer always says
 * *"ক্যাপশন আনা যায়নি"* and never pretends a transcript was read. The pure half is unit-tested against a
 * captured page fragment; the network half has one job and reports one honest failure code.
 */
data class YtInfo(
    val id: String,
    val title: String,
    val channel: String,
    val description: String,
    val durationSec: Int?,
) {
    val url: String get() = YtSummaryCore.canonical(id)
}

object YtSummaryCore {

    /** Any of the shapes people paste: watch?v=, youtu.be/, /shorts/, /embed/, or a bare 11-char id. */
    fun videoId(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val pats = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/live/([A-Za-z0-9_-]{11})"""),
        )
        for (p in pats) p.find(t)?.let { return it.groupValues[1] }
        // A bare id is only accepted when the sentence is basically nothing else.
        return Regex("""\b([A-Za-z0-9_-]{11})\b""").find(t)?.groupValues?.get(1)?.takeIf { t.length <= 16 }
    }

    fun canonical(id: String): String = "https://www.youtube.com/watch?v=$id"

    /** True when the sentence is *about* a YouTube link rather than a YouTube search. */
    fun looksLikeLink(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("youtube.com/") || t.contains("youtu.be/")
    }

    fun isSummaryRequest(text: String): Boolean {
        val t = text.lowercase()
        return listOf("সারমর্ম", "সারাংশ", "সারসংক্ষেপ", "summary", "summarise", "summarize", "কী বলছে", "কি বলছে", "লিংকের")
            .any { t.contains(it) }
    }

    /**
     * Pulls the metadata out of a video page. Deliberately tolerant: Open Graph tags first, then the
     * `ytInitialPlayerResponse` blob, then the itemprops — whichever the page happens to carry.
     */
    fun parse(html: String, id: String): YtInfo? {
        if (html.isBlank()) return null
        val title = meta(html, "og:title")
            ?: jsonString(html, "\"title\":")
            ?: titleTag(html)
            ?: return null
        if (title.contains("Before you continue", ignoreCase = true)) return null // consent wall
        val channel = jsonString(html, "\"ownerChannelName\":")
            ?: jsonString(html, "\"author\":")
            ?: meta(html, "og:site_name")
            ?: ""
        val description = meta(html, "og:description")
            ?: jsonString(html, "\"shortDescription\":")
            ?: ""
        val duration = durationSeconds(jsonString(html, "\"lengthSeconds\":") ?: meta(html, "duration"))
        return YtInfo(
            id = id,
            title = unescape(title).trim(),
            channel = unescape(channel).trim(),
            description = unescape(description).trim(),
            durationSec = duration,
        )
    }

    private fun meta(html: String, prop: String): String? {
        // YouTube ships the same value as property= (Open Graph), name= or itemprop= depending on the page.
        val pats = listOf(
            Regex("""<meta[^>]+(?:property|name|itemprop)=["']$prop["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name|itemprop)=["']$prop["']""", RegexOption.IGNORE_CASE),
        )
        for (p in pats) p.find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun jsonString(html: String, key: String): String? {
        val i = html.indexOf(key)
        if (i < 0) return null
        val rest = html.substring(i + key.length)
        val q = rest.indexOf('"')
        if (q < 0) return null
        val sb = StringBuilder()
        var k = q + 1
        while (k < rest.length) {
            val c = rest[k]
            when {
                c == '\\' && k + 1 < rest.length -> { sb.append(c).append(rest[k + 1]); k += 2; continue }
                c == '"' -> return sb.toString()
                else -> sb.append(c)
            }
            k++
        }
        return null
    }

    private fun titleTag(html: String): String? =
        Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.removeSuffix(" - YouTube")

    /** "PT1H2M3S" / "123" → seconds. */
    fun durationSeconds(raw: String?): Int? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        if (!v.startsWith("PT", ignoreCase = true)) return null
        var total = 0
        var matched = false
        for (m in Regex("""(\d+)([HMS])""", RegexOption.IGNORE_CASE).findAll(v)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            total += when (m.groupValues[2].uppercase()) { "H" -> n * 3600; "M" -> n * 60; else -> n }
            matched = true
        }
        return if (matched) total else null
    }

    fun durationLabel(sec: Int?): String {
        if (sec == null || sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "$m:${pad(s)}"
    }

    private fun pad(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun unescape(s: String): String = s
        .replace("\\u003c", "<").replace("\\u003e", ">").replace("\\\"", "\"")
        .replace("\\/", "/").replace("\\n", " ").replace("\\r", " ")
        .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("\\u0026", "&")

    /** The honest answer: metadata now, captions explicitly not. */
    fun text(info: YtInfo, bengali: Boolean, aiSummary: String? = null): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val head = buildString {
            append("▶ ").append(info.title)
            val dur = durationLabel(info.durationSec)
            if (dur.isNotBlank()) append(" (").append(dur).append(")")
            if (info.channel.isNotBlank()) append(" — ").append(info.channel)
        }
        val body = when {
            !aiSummary.isNullOrBlank() -> "\n" + aiSummary.trim()
            info.description.isNotBlank() -> "\n" + info.description.take(400)
            else -> "\n" + bn("এই ভিডিওতে কোনো বিবরণ দেওয়া নেই।", "This video has no description.")
        }
        return head + body + "\n\n" + noCaptions(bengali) + "\n" + info.url
    }

    fun noCaptions(bengali: Boolean): String = if (bengali) {
        "ক্যাপশন (সাবটাইটেল) পড়া যায়নি — শিরোনাম ও বিবরণ থেকেই সারমর্ম দেওয়া হলো।"
    } else {
        "Captions could not be read — this summary comes from the title and description only."
    }

    fun aiPrompt(bengali: Boolean): String = if (bengali) {
        "নিচে একটি ইউটিউব ভিডিওর শিরোনাম ও বিবরণ দেওয়া হলো। ক্যাপশন নেই, তাই শুধু এই তথ্য থেকেই " +
            "৩ বাক্যে বলো ভিডিওটি কী নিয়ে। যা জানা নেই তা অনুমান করো না।"
    } else {
        "Below is a YouTube video's title and description. There are no captions, so describe in 3 sentences " +
            "what the video is about using only this text. Do not guess beyond it."
    }

    fun failure(reason: String, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        return when (reason) {
            "no_link" -> bn("কোনো ইউটিউব লিংক পাওয়া যায়নি — লিংকটা আবার পাঠান।", "No YouTube link was found — send it again.")
            "consent" -> bn("ইউটিউব পেজ খুলতে সম্মতি পর্দা এসেছে — লিংকটা ব্রাউজারে খুলুন।", "YouTube showed a consent wall — open the link in a browser.")
            "http" -> bn("ইউটিউব পেজ আনা যায়নি (নেটওয়ার্ক)।", "The YouTube page could not be fetched (network).")
            else -> bn("ভিডিওর তথ্য পড়া যায়নি — শিরোনাম পাওয়া যায়নি।", "The video's metadata could not be read — no title found.")
        }
    }
}

/** One request, one honest outcome. Nothing is cached or retried. */
object YtSummary {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    data class Outcome(val info: YtInfo?, val reason: String?)

    fun fetch(id: String): Outcome = try {
        val req = Request.Builder()
            .url(YtSummaryCore.canonical(id))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) VoiceDock AI")
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val info = YtSummaryCore.parse(body, id)
            when {
                info != null -> Outcome(info, null)
                body.contains("Before you continue", ignoreCase = true) -> Outcome(null, "consent")
                else -> Outcome(null, "parse")
            }
        }
    } catch (e: Exception) {
        Outcome(null, if (e is java.io.IOException) "http" else "parse")
    }
}
