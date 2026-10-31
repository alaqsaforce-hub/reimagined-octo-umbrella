package com.voicedock.ai.notify

/**
 * v0.8.0 — "রহিমকে উত্তর দাও" → which sender is that?
 *
 * Fuzzy but conservative: it returns candidates, and the caller asks when there is more than one.
 * Bangla text needs `\p{M}` (combining marks) alongside `\p{L}` — without it, "রহিম" tokenises to
 * garbage (this exact bug was found by the v0.7.0 tests).
 */
object SenderMatch {

    private val stop = setOf(
        "কে", "কে?", "আমি", "আমার", "আমাকে", "the", "to", "for", "message", "reply", "বলো", "উত্তর", "দাও",
    )

    fun tokens(s: String): List<String> = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
        .split(' ')
        .map { it.trim().removeSuffix("কে").removeSuffix("ের").removeSuffix("েরকে") }
        .filter { it.length > 1 && it !in stop }

    /** Strip the Bangla object marker: "রহিমকে" → "রহিম". */
    fun cleanName(s: String): String = s.trim()
        .removeSuffix("কে").removeSuffix("ের").removeSuffix(",").removeSuffix(".")
        .trim()

    /**
     * @return matching senders, best first. Empty = no idea (the caller must ask, not pick one).
     */
    fun resolve(query: String, senders: List<String>, limit: Int = 4): List<String> {
        val q = tokens(cleanName(query))
        if (q.isEmpty() || senders.isEmpty()) return emptyList()
        val scored = senders.mapNotNull { s ->
            val hay = tokens(s)
            val score = q.count { tok -> hay.any { it == tok || it.startsWith(tok) || tok.startsWith(it) } }
            if (score > 0) s to score else null
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
    }
}
