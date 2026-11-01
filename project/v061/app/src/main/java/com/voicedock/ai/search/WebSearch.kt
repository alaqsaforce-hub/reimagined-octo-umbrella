package com.voicedock.ai.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.2.1 — internet search the user pays for and controls.
 *
 * Three providers, chosen because each is honest about its terms: **Brave** and **Tavily** have real free
 * tiers with a key, and **SearxNG** is a URL the user runs (so it works with no account at all). No scraping
 * of Google/Bing: they forbid it, and a scraper breaks every other week.
 *
 * [SearchCore] is pure — request shape, response parsing and the digest that the AI router summarises —
 * so the whole thing is unit-tested without network access. [SearchClient] only does the HTTP call.
 */
enum class SearchProvider(
    val id: String,
    val label: String,
    val needsKey: Boolean,
    val keyUrl: String,
) {
    BRAVE("brave", "Brave Search", true, "https://api-dashboard.search.brave.com/app/keys"),
    TAVILY("tavily", "Tavily", true, "https://app.tavily.com/home"),
    SEARXNG("searxng", "SearxNG (নিজের সার্ভার)", false, ""),
    NONE("none", "কোনো সার্ভার নেই — ব্রাউজারে খুলবে", false, "");

    companion object {
        fun byId(id: String?): SearchProvider = entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class SearchHit(val title: String, val url: String, val snippet: String)

data class SearchRequest(val url: String, val headers: Map<String, String>, val body: String?, val method: String)

/** Pure request building + parsing, one branch per provider. */
object SearchCore {

    const val COUNT = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param searxUrl base URL of a self-hosted SearxNG (may be written without a scheme or a trailing slash)
     * @return null when this provider cannot be called (no key, or no SearxNG URL)
     */
    fun request(provider: SearchProvider, query: String, key: String?, searxUrl: String): SearchRequest? {
        val q = query.trim()
        if (q.isBlank()) return null
        return when (provider) {
            SearchProvider.BRAVE -> key?.takeIf { it.isNotBlank() }?.let { k ->
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                SearchRequest(
                    url = "https://api.search.brave.com/res/v1/web/search?q=$enc&count=$COUNT",
                    headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to k.trim()),
                    body = null,
                    method = "GET",
                )
            }
            SearchProvider.TAVILY -> key?.takeIf { it.isNotBlank() }?.let { k ->
                SearchRequest(
                    url = "https://api.tavily.com/search",
                    headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${k.trim()}"),
                    body = """{"query":${quote(q)},"max_results":$COUNT,"include_answer":false}""",
                    method = "POST",
                )
            }
            SearchProvider.SEARXNG -> {
                val base = normaliseBase(searxUrl) ?: return null
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                SearchRequest(
                    url = "$base/search?q=$enc&format=json&language=all",
                    headers = mapOf("Accept" to "application/json"),
                    body = null,
                    method = "GET",
                )
            }
            SearchProvider.NONE -> null
        }
    }

    /** `192.168.0.4:8888/` → `http://192.168.0.4:8888` — the same courtesy the AI section gives. */
    fun normaliseBase(raw: String): String? {
        var s = raw.trim()
        if (s.isBlank()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        s = s.trimEnd('/')
        if (s.endsWith("/search")) s = s.dropLast("/search".length)
        return if (s.length <= "https://".length) null else s
    }

    fun parse(provider: SearchProvider, body: String, limit: Int = COUNT): List<SearchHit> = runCatching {
        when (provider) {
            SearchProvider.BRAVE -> {
                val web = json.parseToJsonElement(body).jsonObject["web"]?.jsonObject ?: return emptyList()
                (web["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val o = e.jsonObject
                    hit(o["title"]?.jsonPrimitive?.contentOrNull, o["url"]?.jsonPrimitive?.contentOrNull, o["description"]?.jsonPrimitive?.contentOrNull)
                }
            }
            SearchProvider.TAVILY -> (json.parseToJsonElement(body).jsonObject["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                hit(o["title"]?.jsonPrimitive?.contentOrNull, o["url"]?.jsonPrimitive?.contentOrNull, o["content"]?.jsonPrimitive?.contentOrNull)
            }
            SearchProvider.SEARXNG -> (json.parseToJsonElement(body).jsonObject["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                hit(o["title"]?.jsonPrimitive?.contentOrNull, o["url"]?.jsonPrimitive?.contentOrNull, o["content"]?.jsonPrimitive?.contentOrNull)
            }
            SearchProvider.NONE -> emptyList()
        }.take(limit)
    }.getOrDefault(emptyList())

    private fun hit(title: String?, url: String?, snippet: String?): SearchHit? {
        if (title.isNullOrBlank() || url.isNullOrBlank() || !url.startsWith("http")) return null
        return SearchHit(title.trim(), url.trim(), clean(snippet.orEmpty()))
    }

    /** Search snippets arrive with HTML entities and stray whitespace. */
    fun clean(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * The text handed to the AI router: numbered snippets only, no page bodies. The model answers from these
     * and nothing else, and [sourcesLine] prints the links so a claim can be checked.
     */
    fun digest(hits: List<SearchHit>): String = buildString {
        hits.take(COUNT).forEachIndexed { i, h ->
            append("${i + 1}. ${h.title}\n${h.snippet.take(400)}\n${h.url}\n\n")
        }
    }.trim()

    fun sourcesLine(hits: List<SearchHit>, bengali: Boolean): String {
        if (hits.isEmpty()) return ""
        val links = hits.take(4).joinToString("\n") { "• ${it.title.take(60)} — ${it.url}" }
        return (if (bengali) "\n\nসূত্র:\n" else "\n\nSources:\n") + links
    }

    fun emptyText(bengali: Boolean): String =
        if (bengali) "এই মুহূর্তে কিছু ফলাফল পাওয়া যায়নি — অন্য ভাষায় বা অন্যভাবে খুঁজে দেখুন।"
        else "No results came back — try a different wording or language."

    /** No provider configured: the browser is a real answer, so say that instead of failing. */
    fun browserHint(query: String, bengali: Boolean): String {
        val enc = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return if (bengali)
            "সার্চ সার্ভার সেট করা নেই — ব্রাউজারে ‘$query’ খুলছি। সেটিংস → সার্চে Brave/Tavily/SearxNG দিলে সারমর্মও বলতে পারব।\nhttps://duckduckgo.com/?q=$enc"
        else
            "No search provider is set — opening ‘$query’ in the browser. Add Brave/Tavily/SearxNG in Settings → Search and I can summarise the results too.\nhttps://duckduckgo.com/?q=$enc"
    }

    fun browserUrl(query: String): String =
        "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }

    /** The prompt for the summariser: short, sourced, and allowed to say "not in the results". */
    fun prompt(query: String, hits: List<SearchHit>, bengali: Boolean): String = buildString {
        if (bengali) {
            append("নিচের সার্চ ফলাফল দেখে ‘$query’ সম্পর্কে ৩–৪ বাক্যে বাংলায় উত্তর দাও। ")
            append("শুধু ফলাফলে থাকা তথ্য ব্যবহার করো; না থাকলে স্পষ্ট বলো। লিংক লিখো না — সেগুলো পরে দেখানো হবে।")
        } else {
            append("Answer in 3–4 sentences about ‘$query’ using ONLY the search results below. ")
            append("If the results do not contain the answer, say so plainly. Do not write links.")
        }
        append("\n\n").append(digest(hits))
    }.toString()
}
