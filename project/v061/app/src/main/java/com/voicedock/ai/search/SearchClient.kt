package com.voicedock.ai.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.SettingsStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * v1.2.1 — the network half of [SearchCore]. No retries, no caching, no cleverness: one request, one honest
 * outcome ([SearchOutcome.reason] is a code the UI turns into a sentence).
 */
data class SearchOutcome(val hits: List<SearchHit>, val reason: String?) {
    val ok: Boolean get() = reason == null
}

object SearchClient {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun keyName(p: SearchProvider): String = "search_key_${p.id}"

    fun key(ctx: Context, p: SearchProvider): String? =
        if (p.needsKey) SecretStore.get(ctx, keyName(p)) else null

    /** Configured = a provider that can actually be called right now. */
    fun configured(ctx: Context, s: AppSettings = SettingsStore.state.value): Boolean {
        val p = SearchProvider.byId(s.searchProvider)
        return when {
            p == SearchProvider.SEARXNG -> SearchCore.normaliseBase(s.searxUrl) != null
            p.needsKey -> !key(ctx, p).isNullOrBlank()
            else -> false
        }
    }

    fun run(ctx: Context, query: String, s: AppSettings = SettingsStore.state.value): SearchOutcome {
        val p = SearchProvider.byId(s.searchProvider)
        if (p == SearchProvider.NONE) return SearchOutcome(emptyList(), "not_configured")
        val req = SearchCore.request(p, query, key(ctx, p), s.searxUrl)
            ?: return SearchOutcome(emptyList(), if (p.needsKey) "no_key" else "no_url")
        return try {
            val builder = Request.Builder().url(req.url)
            req.headers.forEach { (k, v) -> builder.header(k, v) }
            val request = if (req.method == "POST") {
                builder.post((req.body.orEmpty()).toRequestBody(JSON)).build()
            } else {
                builder.get().build()
            }
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> {
                        val hits = SearchCore.parse(p, text)
                        if (hits.isEmpty()) SearchOutcome(hits, "empty") else SearchOutcome(hits, null)
                    }
                    r.code == 401 || r.code == 403 -> SearchOutcome(emptyList(), "auth")
                    r.code == 429 -> SearchOutcome(emptyList(), "limit")
                    else -> SearchOutcome(emptyList(), "http_${r.code}")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            SearchOutcome(emptyList(), "timeout")
        } catch (e: IOException) {
            SearchOutcome(emptyList(), "network")
        } catch (e: Exception) {
            SearchOutcome(emptyList(), "error")
        }
    }

    /** User-facing sentence for a reason code. */
    fun explain(reason: String?, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        return when {
            reason == null -> ""
            reason == "not_configured" -> bn("সার্চ সার্ভার সেট করা নেই (সেটিংস → সার্চ)", "No search provider is set (Settings → Search)")
            reason == "no_key" -> bn("সার্চ key দেওয়া হয়নি", "The search key is missing")
            reason == "no_url" -> bn("SearxNG-এর ঠিকানা দিন", "Enter the SearxNG URL")
            reason == "auth" -> bn("সার্চ key ভুল বা মেয়াদোত্তীর্ণ", "The search key is wrong or expired")
            reason == "limit" -> bn("সার্চ সার্ভারের ফ্রি সীমা শেষ — পরে চেষ্টা করুন", "The search free tier is used up — try later")
            reason == "timeout" -> bn("সার্চ সার্ভার সময়মতো উত্তর দেয়নি", "The search server timed out")
            reason == "network" -> bn("ইন্টারনেট সংযোগ নেই", "No internet connection")
            reason == "empty" -> SearchCore.emptyText(bengali)
            else -> bn("সার্চ ব্যর্থ ($reason)", "Search failed ($reason)")
        }
    }

    fun openBrowser(ctx: Context, query: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SearchCore.browserUrl(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * v1.2.1 — "ইউটিউবে X খোঁজো": the official search intent, with the browser as fallback.
 *
 * Honest limit: this opens the results. Choosing *the first video* needs YouTube's own UI (or the
 * accessibility pass planned for v1.3), so the answer says "প্রথম ভিডিওতে চাপ দিন" instead of pretending.
 */
object YouTubeSearch {

    fun url(query: String): String =
        "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

    fun appUrl(query: String): String =
        "vnd.youtube://results?search_query=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

    /** Returns true when a YouTube app handled the intent, false when the browser was used. */
    fun open(ctx: Context, query: String): Boolean {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(appUrl(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { ctx.startActivity(app); true }.getOrDefault(false)
        if (opened) return true
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            false
        }.getOrDefault(false)
    }

    fun answer(query: String, inApp: Boolean, bengali: Boolean): String = if (bengali) {
        val where = if (inApp) "ইউটিউব অ্যাপে" else "ব্রাউজারে"
        "$where ‘$query’ খুঁজে খুলে দিলাম — প্রথম ভিডিওতে চাপ দিন (ভিডিও বেছে নেওয়া এখনো নিজে করতে হবে)।"
    } else {
        val where = if (inApp) "in the YouTube app" else "in the browser"
        "Opened ‘$query’ $where — tap the first video (picking it for you is still manual)."
    }
}
