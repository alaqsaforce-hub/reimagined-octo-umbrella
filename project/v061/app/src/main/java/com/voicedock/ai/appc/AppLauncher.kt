package com.voicedock.ai.appc

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** One launchable app, with the labels we can match against. */
data class AppEntry(val pkg: String, val label: String, val keywords: List<String>)

/**
 * v0.7.0 — "হোয়াটসঅ্যাপ খোলো" / "open YouTube".
 *
 * Resolution is a pure name match ([AppNames.match]) over the apps the phone reports, plus a small
 * alias table because recognisers write "whatsapp", "হোয়াটস্যাপ", "wats app" and "YouTube" all
 * differently. No root, no UI automation: a plain launch intent.
 */
object AppNames {

    /** Bangla/English/sloppy aliases → fragments that appear in the real package or label. */
    private val aliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("whatsapp", "whats app", "watsapp", "wats up", "হোয়াটসঅ্যাম", "হোয়াটসঅ্যাপ", "হোয়াটসাপ", "হোয়াটস্যাp"),
        "youtube" to listOf("youtube", "ইউটিউব", "you tube"),
        "facebook" to listOf("facebook", "ফেসবুক"),
        "messenger" to listOf("messenger", "মেসেঞ্জার"),
        "instagram" to listOf("instagram", "ইনস্টাগ্রাম"),
        "telegram" to listOf("telegram", "টেলিগ্রাম"),
        "spotify" to listOf("spotify", "স্পটিফাই"),
        "camera" to listOf("camera", "ক্যামেরা"),
        "settings" to listOf("settings", "সেটিংস"),
        "phone" to listOf("phone", "dialer", "ফোন", "ডায়ালার"),
        "sms" to listOf("messages", "sms", "মেসেজ"),
        "gallery" to listOf("gallery", "photos", "গ্যালারি", "ছবি"),
        "chrome" to listOf("chrome", "ক্রোম", "browser", "ব্রাউজার"),
        "gmail" to listOf("gmail", "জিমেইল"),
    )

    fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Best matches for a spoken query. Exact label match first, then prefix, then contains; at most
     * [limit]. Empty means "we have no idea" and the caller must ask instead of guessing.
     */
    fun match(query: String, apps: List<AppEntry>, limit: Int = 4): List<AppEntry> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val expanded = (listOf(q) + (aliases[q] ?: aliases.entries.firstOrNull { q.contains(it.key) || it.value.any { a -> q.contains(a) } }?.value ?: emptyList()))
            .map { normalize(it) }.filter { it.isNotBlank() }.distinct()

        fun score(a: AppEntry): Int {
            val label = normalize(a.label)
            val pkg = normalize(a.pkg).replace("com ", " ").trim()
            var best = 0
            for (term in expanded) {
                if (label == term) best = maxOf(best, 100)
                else if (pkg == term || pkg.endsWith(" $term") || pkg == term) best = maxOf(best, 90)
                else if (label.startsWith(term)) best = maxOf(best, 80)
                else if (label.contains(term)) best = maxOf(best, 60)
                else if (pkg.contains(term)) best = maxOf(best, 50)
                else if (a.keywords.any { normalize(it).contains(term) }) best = maxOf(best, 40)
                else if (term.length >= 4 && label.split(' ').any { w -> w.startsWith(term.take(4)) }) best = maxOf(best, 30)
            }
            return best
        }

        return apps.mapNotNull { a -> score(a).takeIf { it > 0 }?.let { a to it } }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label })
            .map { it.first }
            .distinctBy { it.pkg }
            .take(limit)
    }
}

class AppLauncher(private val ctx: Context) {

    /** Launchable apps. On API 30+ this needs QUERY_ALL_PACKAGES to see third-party apps. */
    fun installed(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<android.content.pm.ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == ctx.packageName) return@mapNotNull null
            AppEntry(pkg, ri.loadLabel(pm).toString(), listOf(pkg))
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun resolve(query: String): List<AppEntry> = AppNames.match(query, installed())

    /** @return true when a window was actually opened. */
    fun launch(pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    fun labelOf(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
