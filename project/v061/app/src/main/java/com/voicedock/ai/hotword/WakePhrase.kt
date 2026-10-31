package com.voicedock.ai.hotword

/**
 * v1.0.0 — the wake word: **"শাহরুখ" / "Shahrukh"**, after the film star.
 *
 * Everything here is pure: recognition returns a string, and deciding whether that string *is* the wake
 * word is the part that must be right on the first try — a false negative means the assistant ignores you,
 * a false positive means it starts listening while you are talking to someone else.
 *
 * Matching rules, in order:
 *  1. **exact forms** — every spelling a person or a recogniser produces ("শাহরুখ", "শাহরুখ খান",
 *     "shah rukh", "shahrukh khan", "শারুক", "sharukh"…) matched on a normalised string;
 *  2. **one edit away** — a single substituted/inserted/deleted character is tolerated for a word of five
 *     or more characters, because phone recognisers mishear names constantly;
 *  3. anything else is not a wake word. "শাহরুখের সিনেমা দেখতে চাই" (a film request) is deliberately **not**
 *     a wake word: the trailing possessive makes it a different word.
 *
 * Normalisation is length-preserving (one character in, one character out) so an index found in the
 * normalised string can be used to cut the *original* utterance — that is how "শাহরুখ, ওয়াটসঅ্যাপ খোলো"
 * becomes the wake word plus the command "ওয়াটসঅ্যাপ খোলো" in one breath.
 */
object WakePhrase {

    /** Shown in notifications and settings. */
    const val BN = "শাহরুখ"
    const val EN = "Shahrukh"

    /** Every accepted spelling. Longer forms first, so "শাহরুখ খান" wins over "শাহরুখ". */
    private val forms: List<String> = listOf(
        "শাহরুখ খান", "শাহরূখ খান", "শাহরুখখান",
        "শাহরুখ", "শাহরূখ", "শাহ রুখ", "শাহরুক", "শারুখ", "সারুখ", "শারুক",
        "shahrukh khan", "shah rukh khan", "shahrukhkhan",
        "shahrukh", "shah rukh", "shah rookh", "shahruk", "sharukh", "shahrukhshahrukh",
        "sahrukh", "sha rukh",
    ).sortedByDescending { it.length }

    /** Canonical tokens used for the one-edit tolerance. */
    private val canonical = listOf("শাহরুখ", "shahrukh")

    /**
     * Length-preserving normalisation: Bengali digits → ASCII, both cases → lower, punctuation → space.
     * `"শাহরুখ!" == "শাহরুখ "` and every index still points at the same character of the input.
     */
    fun softNormalize(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when {
                    c in '০'..'৯' -> '0' + (c - '০')
                    // Combining marks MUST survive: without the Bengali vowel signs "শাহরুখ" becomes
                    // "শ হর খ" and the wake word can never match. (Caught by a test, v1.0.0.)
                    c.isLetterOrDigit() || c.isWhitespace() || isMark(c) -> c.lowercaseChar()
                    else -> ' '
                },
            )
        }
    }

    /** A combining mark: Bengali vowel signs (Mn/Mc) live here. */
    private fun isMark(c: Char): Boolean {
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Where the wake word was found. [exact] = matched a known spelling, not the fuzzy fallback. */
    data class Hit(val index: Int, val length: Int, val exact: Boolean)

    /** The wake word in [utterance], or null. */
    fun hit(utterance: String): Hit? {
        if (utterance.isBlank()) return null
        val n = softNormalize(utterance)
        for (f in forms) {
            var from = 0
            while (true) {
                val i = n.indexOf(f, from)
                if (i < 0) break
                // The match must be a whole word: "শাহরুখের" (Shahrukh's film) is a different word.
                if (isBoundary(n, i - 1) && isBoundary(n, i + f.length)) return Hit(i, f.length, exact = true)
                from = i + 1
            }
        }
        // Fuzzy: one edit on a single word of at least five characters.
        var start = 0
        for (word in n.split(' ')) {
            if (word.length >= 5 && canonical.any { near(word, it) }) return Hit(start, word.length, exact = false)
            start += word.length + 1
        }
        return null
    }

    /** True when [i] is outside the string or not part of a word (space, punctuation, end). */
    private fun isBoundary(s: String, i: Int): Boolean {
        if (i < 0 || i >= s.length) return true
        val c = s[i]
        // "শাহরুখের" (Shahrukh's …) is a different word, so a following letter OR vowel sign fails the check.
        return !(c.isLetterOrDigit() || isMark(c))
    }

    fun matches(utterance: String): Boolean = hit(utterance) != null

    /**
     * What was said *after* the wake word, i.e. a command spoken in the same breath.
     * Empty when the user only said the wake word (the normal case: then we start listening).
     */
    fun remainder(utterance: String): String {
        val h = hit(utterance) ?: return ""
        if (h.index + h.length > utterance.length) return ""
        val rest = utterance.substring(h.index + h.length).trim().trim(',', '।', '.', '!', '?', ' ', '—', '-')
        return if (rest.any { it.isLetterOrDigit() }) rest else ""
    }

    /**
     * One edit away, compared on the *raw* words — marks included. That difference matters: "শাহরুখের"
     * (Shahrukh's) is two edits away from "শাহরুখ" and is therefore not a wake word, while "শাহরখ" (a
     * dropped vowel sign) is exactly one.
     */
    private fun near(word: String, form: String): Boolean {
        if (word.length < 4 || form.length < 4) return false
        if (kotlin.math.abs(word.length - form.length) > 1) return false
        return editDistance(word, form) <= 1
    }

    /** Plain Levenshtein; the strings here are single words, so the O(n·m) table is tiny. */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
