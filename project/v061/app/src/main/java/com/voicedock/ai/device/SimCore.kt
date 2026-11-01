package com.voicedock.ai.device

/**
 * v1.3.0 — "কোন সিম থেকে কল দেব?" — the device's own topology, as data.
 *
 * A dual-SIM phone has two subscriptions, each with its own slot index, carrier and phone number, plus a
 * system default for calls. The voice command must be able to say "সিম ২ দিয়ে" and mean it, and the app must be
 * able to answer "আমার নম্বর কত" without guessing. [SimCore] is the pure half: it turns a list of subscriptions
 * into a decision and a sentence; [DeviceAccess] only reads the OS.
 */
data class SimInfo(
    /** 0-based physical slot index as the OS reports it (usually 0 and 1). */
    val slot: Int,
    /** 1-based number as people say it ("সিম ১", "সিম ২"). */
    val displaySlot: Int,
    val subscriptionId: Int,
    val carrier: String,
    val label: String,
    /** The number stored on the SIM, when READ_PHONE_NUMBERS allows it. */
    val number: String? = null,
    val isDefaultVoice: Boolean = false,
    val isDefaultData: Boolean = false,
    val active: Boolean = true,
) {
    val title: String get() = buildString {
        append("সিম ${SimCore.bn(displaySlot)}")
        if (carrier.isNotBlank()) append(" · $carrier")
        if (label.isNotBlank() && label != carrier) append(" ($label)")
        if (!active) append(" · বন্ধ")
    }
}

object SimCore {

    /** Bengali digits, so the Settings list reads like the spoken commands. */
    fun bn(s: Any): String = buildString {
        for (c in s.toString()) {
            if (c in '0'..'9') append("০১২৩৪৫৬৭৮৯"[c - '0']) else append(c)
        }
    }

    fun asciiDigits(s: String): String = buildString {
        for (c in s) {
            val i = "০১২৩৪৫৬৭৮৯".indexOf(c)
            append(if (i >= 0) ('0' + i) else c)
        }
    }

    /** Only active, call-capable subscriptions, in slot order. */
    fun usable(sims: List<SimInfo>): List<SimInfo> = sims.filter { it.active }.sortedBy { it.slot }

    /**
     * Which subscription a spoken request means.
     *
     * @param requestedSlot 1-based ("সিম ২" → 2); null = no preference
     * @return the chosen SIM, or null when the request cannot be honoured (that becomes a question, never a
     *         silent fallback to the wrong SIM)
     */
    fun pick(sims: List<SimInfo>, requestedSlot: Int?): SimInfo? {
        val list = usable(sims)
        if (list.isEmpty()) return null
        if (requestedSlot == null) return list.firstOrNull { it.isDefaultVoice } ?: list.first()
        return list.firstOrNull { it.displaySlot == requestedSlot }
    }

    /** Why [pick] returned null — for the answer the user hears. */
    fun explainMissing(sims: List<SimInfo>, requestedSlot: Int, bengali: Boolean): String {
        val list = usable(sims)
        fun bn(b: String, e: String) = if (bengali) b else e
        if (list.isEmpty()) return bn(
            "এই ফোনে চালু কোনো সিম পাওয়া যায়নি (অনুমতি নেই বা সিম নেই)।",
            "No active SIM is visible (permission missing or no SIM).",
        )
        val have = list.joinToString(", ") { "সিম ${bn(it.displaySlot)}" }
        return bn(
            "সিম ${bn(requestedSlot)} নেই — আছে: $have।",
            "There is no SIM $requestedSlot — you have: $have.",
        )
    }

    /** The sentence shown/asked before a call from a specific SIM. */
    fun describeTarget(sim: SimInfo?, name: String, bengali: Boolean): String = if (bengali) {
        "${name} কে" + (sim?.let { " (${it.title})" } ?: "") + " কল করা হবে"
    } else {
        "call $name" + (sim?.let { " (${it.title})" } ?: "")
    }

    /** "আমার নম্বর: সিম ১ — 017…, সিম ২ — 019…" or an honest reason why it is unknown. */
    fun myNumbers(sims: List<SimInfo>, bengali: Boolean): String {
        val list = usable(sims)
        fun bn(b: String, e: String) = if (bengali) b else e
        if (list.isEmpty()) return bn("কোনো সিম পাওয়া যায়নি।", "No SIM found.")
        val known = list.filter { !it.number.isNullOrBlank() }
        if (known.isEmpty()) return bn(
            "সিমের নম্বর পড়ার অনুমতি নেই (READ_PHONE_NUMBERS) — সেটিংস → সিস্টেম ও এজেন্ট থেকে দিন।",
            "No permission to read the SIM numbers (READ_PHONE_NUMBERS) — grant it in Settings → System & agent.",
        )
        return buildString {
            append(bn("আমার নম্বর", "My numbers"))
            known.forEach { append("\n• সিম ${bn(it.displaySlot)} (${it.carrier.ifBlank { "—" }}): ${mask(it.number!!)}") }
        }.toString()
    }

    /** Never print a full number in a chat bubble that may be screenshotted — last 4 digits are enough. */
    fun mask(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.length <= 4) return number
        return number.filter { !it.isDigit() } + "••••" + digits.takeLast(4)
    }

    /**
     * Removes the SIM part of a sentence before the rest is treated as a contact name:
     * "সিম ২ দিয়ে রহিমকে" → "রহিমকে". Without this the call target would be the words "সিম ২ দিয়ে".
     */
    fun stripSimPhrase(text: String): String {
        var t = asciiDigits(text)
        val pats = listOf(
            Regex("(সিম|sim|slot)\\s*\\d\\s*(নম্বর)?\\s*(দিয়ে|দিয়ে|থেকে|diye|theke)?"),
            Regex("\\d\\s*(নম্বর)?\\s*(সিম|sim)\\s*(দিয়ে|দিয়ে|থেকে|diye|theke)?"),
            Regex("(দ্বিতীয়|দোসরা|second|প্রথম|first)\\s*(সিম|sim)\\s*(দিয়ে|দিয়ে|থেকে|diye|theke)?"),
            Regex("(এই|ওই|that|this)\\s*(সিম|sim)\\s*(দিয়ে|দিয়ে|diye)?"),
        )
        for (p in pats) t = p.replace(t, " ")
        // "on sim 2 Rahim" / "সিম ২ দিয়ে রহিমকে" → the preposition is part of the phrase, not of the name.
        t = Regex("^\\s*(on|from|via|using|with|দিয়ে|দিয়ে)\\s+", RegexOption.IGNORE_CASE).replace(t, " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Parses "সিম ২", "sim 2", "২ নম্বর সিম", "second sim" → 2. Null when the sentence has no SIM word. */
    fun parseSlot(text: String): Int? {
        val t = asciiDigits(text.lowercase())
        if (!Regex("সিম|sim|slot").containsMatchIn(t)) return null
        Regex("(সিম|sim|slot)\\s*(\\d)").find(t)?.let { return it.groupValues[2].toIntOrNull()?.takeIf { n -> n in 1..4 } }
        Regex("(\\d)\\s*(নম্বর)?\\s*(সিম|sim)").find(t)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..4 } }
        if (Regex("দ্বিতীয়|second|দোসরা").containsMatchIn(t)) return 2
        if (Regex("প্রথম|first").containsMatchIn(t)) return 1
        return null
    }
}
