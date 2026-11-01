package com.voicedock.ai.command

import com.voicedock.ai.schedule.TimeWords

/**
 * v0.7.0 — sentence → [CommandSpec].
 *
 * Two routes, cheapest first:
 *  1. [offline] — deterministic Bangla/English patterns. Runs always, works without internet or an AI key.
 *  2. the model — [prompt] asks for one JSON object; [CommandJson] parses it strictly. Only used when the
 *     offline pass finds nothing, so a normal command never depends on the network.
 *
 * If both fail the result is [Intent.UNKNOWN] and the pipeline asks the user instead of acting.
 */
object CommandParser {

    private const val MIN_CONFIDENCE = 0.7

    /**
     * Display/cleanup form: Bengali digits become ASCII, punctuation becomes a space, spacing collapses.
     * NOT used for extracting slot values — it changes the string length (see [softNormalize]).
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    in '০'..'৯' -> ('0' + (c - '০'))
                    '।', '?', '!', ',', '.', ';', ':', '"', '\'', '‘', '’', '“', '”' -> ' '
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Length-preserving form (one char in, one char out), so an index found here can be used to cut the
     * ORIGINAL sentence. Only Bengali digits are mapped; everything else is untouched.
     */
    fun softNormalize(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '০'..'৯') ('0' + (c - '০')) else c)
    }

    /** Deterministic patterns. Confidence 0.95 = explicit phrasing, 0.8 = weaker hint. */
    fun offline(raw: String): CommandSpec {
        // Matching uses a length-preserving form so slot values can be cut from the user's own words.
        val low = softNormalize(raw).lowercase()
        if (low.isBlank()) return CommandSpec.unknown(raw)

        fun spec(i: Intent, conf: Double, vararg slots: Pair<Slot, String>) =
            CommandSpec.of(i, raw, conf, *slots)

        // --- reminders (v1.2.0): the goal and the time are split offline, before anything else, because
        // "মনে করিয়ে দাও" also contains the "মনে" that the memory rules look for.
        if (ReminderSpeech.looksLike(low)) {
            if (ReminderSpeech.isListRequest(raw)) return spec(Intent.REMINDER_LIST, 0.93)
            val goal = ReminderSpeech.goal(raw)
            val hasTime = TimeWords.parse(raw, System.currentTimeMillis()) != null
            return CommandSpec(
                intent = Intent.REMINDER_ADD,
                slots = buildMap {
                    if (goal.isNotBlank()) put(Slot.TEXT, goal)
                    if (hasTime) put(Slot.TIME, raw)
                },
                confidence = 0.92,
                raw = raw,
            )
        }
        // --- undo (v0.10.0): one step back, only for what can honestly be reversed ---
        if (containsAny(low, "আনডু", "আনডো", "undo", "ফিরিয়ে নাও")) {
            return spec(Intent.UNDO, 0.95)
        }
        // --- memory recall (must come before "রাখো") ---
        if (containsAny(low, "মনে পড়", "মনে আছে", "কী বলেছিলাম", "কি বলেছিলাম", "মনে রাখতে বলেছিলাম", "recall", "what did you remember", "what did i tell you")) {
            return spec(Intent.MEMORY_RECALL, 0.95, Slot.QUERY to raw.trim())
        }
        // --- remember ---
        val remember = listOf("মনে রাখো", "মনে রাখ ", "মনে রাখ", "remember that", "remember ")
        for (k in remember) {
            val i = low.indexOf(k)
            if (i >= 0) {
                // Cut the ORIGINAL text so "৩টায়" keeps its Bengali digit in the stored memory.
                val rest = raw.substring(i + k.length).trim()
                return spec(Intent.MEMORY_STORE, 0.95, Slot.TEXT to rest.ifBlank { raw.trim() })
            }
        }
        // --- read aloud ---
        if (containsAny(low, "পড়ে শোনাও", "পড়ে শোনা", "জোরে পড়", "read aloud", "read it out", "speak it")) {
            return spec(Intent.SPEAK, 0.95, Slot.TEXT to afterAny(raw, low, "পড়ে শোনাও", "পড়ে শোনা", "জোরে পড়", "read aloud", "read it out", "speak it"))
        }
        // --- reply to a sender (v0.8): "রহিমকে বলো আমি পরে ফোন করব" / "tell Rahim I'll call later" ---
        val replyVerbs = arrayOf("বলো যে", "কে বলো", "বলো ", "উত্তর দাও", "রিপ্লাই দাও", "মেসেজ দাও", "reply to", "tell ", "message ")
        if (containsAny(low, *replyVerbs)) {
            for (v in replyVerbs) {
                val i = low.indexOf(v)
                if (i < 0) continue
                // Bangla puts the person before the verb ("রহিমকে বলো …"), English after it ("tell Karim …").
                val latinVerb = v.first().code < 128
                var who = if (latinVerb) "" else beforeAny(raw, low, v)
                var body = afterAnyFrom(raw, low, i + v.length)
                if (latinVerb) {
                    val first = body.substringBefore(' ')
                    if (first.isNotBlank()) { who = first; body = body.removePrefix(first).trim() }
                }
                if (who.isNotBlank() && body.isNotBlank()) {
                    val contact = com.voicedock.ai.notify.SenderMatch.cleanName(who)
                    // v1.2.1 — a stated time turns "tell them" into "tell them later".
                    if (TimeWords.parse(raw, System.currentTimeMillis()) != null) {
                        return spec(Intent.MSG_SCHEDULE, 0.9, Slot.CONTACT to contact, Slot.TEXT to body, Slot.TIME to raw)
                    }
                    return spec(Intent.MSG_REPLY, 0.9, Slot.CONTACT to contact, Slot.TEXT to body)
                }
            }
        }
        // --- read messages aloud ---
        if (containsAny(low, "মেসেজ পড়", "মেসেজগুলো পড়", "শেষ মেসেজ", "read messages", "read my messages", "শেষ পাঁচটা", "শেষ ৫টা")) {
            return spec(Intent.MSG_READ, 0.9, Slot.QUERY to raw.trim())
        }
        // --- calls (recognised, executed from v0.9) ---
        if (containsAny(low, "কেটে দাও", "কেটে দে", "কাটো", "কল কাট", "decline", "reject call", "reject the call")) {
            return spec(Intent.CALL_DECLINE, 0.9)
        }
        if (containsAny(low, "রিং হচ্ছে", "ধরো", "কল ধর", "ফোন ধর", "answer the call", "answer it", "pick up", "receive the call")) {
            return spec(Intent.CALL_ANSWER, 0.9)
        }
        if (containsAny(low, "ফোন কর", "ফোন দাও", "কল কর", "call mom", "call ") || low.trim().endsWith("call")) {
            // Bangla puts the person first: "মাকে ফোন কর" → the name is BEFORE the verb.
            val after = afterAny(raw, low, "ফোন কর", "ফোন দাও", "কল কর", "call")
            // "রহিমকে ফোন করো" leaves only the vowel sign of "করো" behind — that is not a name.
            val who = if (meaningful(after)) after else beforeAny(raw, low, "ফোন কর", "ফোন দাও", "কল কর", "call")
            return spec(Intent.CALL_PLACE, 0.9, Slot.CONTACT to who)
        }
        // --- YouTube search (v1.2.1): before the generic web search, so "ইউটিউবে …" stays on YouTube. ---
        if ((low.contains("ইউটিউব") || low.contains("youtube")) &&
            containsAny(low, "খোঁজো", "খুঁজে", "সার্চ", "search")) {
            val q = afterAny(raw, low, "ইউটিউবে", "youtube এ", "youtube", "খোঁজো", "খুঁজে দাও", "সার্চ করো", "search")
                .ifBlank { beforeAny(raw, low, "ইউটিউবে", "youtube", "খোঁজো", "search") }
            return spec(Intent.YT_SEARCH, 0.9, Slot.QUERY to searchQuery(q).ifBlank { searchQuery(raw) })
        }
        // --- internet search (v1.2.1) ---
        if (containsAny(low, "সার্চ কর", "সার্চ দাও", "সার্চ করে", "খুঁজে দাও", "খোঁজো", "search for", "search the web", "গুগলে", "google ")) {
            val q = afterAny(raw, low, "সার্চ করো", "সার্চ কর", "সার্চ দাও", "সার্চ করে", "খুঁজে দাও", "খোঁজো", "search for", "search the web", "search", "গুগলে", "google")
                .ifBlank { beforeAny(raw, low, "সার্চ করো", "সার্চ কর", "খুঁজে দাও", "খোঁজো", "search", "গুগলে", "google") }
            return spec(Intent.WEB_SEARCH, 0.88, Slot.QUERY to searchQuery(q).ifBlank { searchQuery(raw) })
        }
        // --- messages (v0.8) ---
        if (containsAny(low, "সারমর্ম", "কে কে মেসেজ", "কে মেসেজ দিয়েছে", "মেসেজ দিয়েছে", "summarise", "summarize", "digest")) {
            return spec(Intent.MSG_DIGEST, 0.9, Slot.QUERY to raw.trim())
        }
        // --- media (v0.10): the action is extracted here; the session is found at execution time. ---
        if (com.voicedock.ai.media.MediaText.looksLikeMedia(low)) {
            val action = com.voicedock.ai.media.MediaText.action(raw)
            // No action ("গান") stays vague on purpose: the pipeline asks instead of guessing play vs pause.
            return if (action == null) spec(Intent.MEDIA, 0.9, Slot.QUERY to raw.trim())
            else spec(Intent.MEDIA, 0.95, Slot.MEDIA to action.name.lowercase(), Slot.QUERY to raw.trim())
        }
        // --- app launch ---
        if (containsAny(low, "খোলো", "খুলে দাও", "খোল ", "ওপেন", "open ", "launch ", "চালু কর")) {
            val after = afterAny(raw, low, "খোলো", "খুলে দাও", "খোল", "ওপেন", "open", "launch", "চালু কর")
            val app = cleanAppQuery(after).ifBlank { cleanAppQuery(beforeAny(raw, low, "খোলো", "খুলে দাও", "ওপেন", "open", "launch", "চালু কর")) }
            return spec(Intent.APP_LAUNCH, 0.9, Slot.APP to app)
        }
        // --- explicit "ask the AI" ---
        if (containsAny(low, "ai কে জিজ্ঞাসা", "এআই কে জিজ্ঞাসা", "ask ai", "ask the ai")) {
            return spec(Intent.ASK_AI, 0.9, Slot.QUERY to afterAny(raw, low, "ai কে জিজ্ঞাসা", "এআই কে জিজ্ঞাসা", "ask the ai", "ask ai"))
        }
        return CommandSpec.unknown(raw)
    }

    /** True when the offline pass found something worth acting on without the model. */
    fun confident(spec: CommandSpec): Boolean = spec.intent != Intent.UNKNOWN && spec.confidence >= MIN_CONFIDENCE

    /** Prompt for the model: fixed schema, closed intent list, JSON only. */
    fun prompt(bengali: Boolean): String = buildString {
        append("You convert ONE spoken command into JSON. Reply with JSON only, no prose, no markdown fences.\n")
        append("Schema: {\"intent\":\"<one of the ids>\",\"slots\":{...},\"confidence\":0.0-1.0}\n")
        append("Allowed intents and slots:\n")
        for (i in CommandRegistry.entries()) {
            val meta = CommandRegistry.meta(i)
            append("- ").append(i.id)
            if (meta.requiredSlots.isNotEmpty()) append(" (needs: ").append(meta.requiredSlots.joinToString(",") { it.key }).append(")")
            append(" — ").append(if (bengali) meta.helpBn else meta.helpEn).append('\n')
        }
        append("Rule: if the sentence is not clearly one of these, return {\"intent\":\"unknown\",\"confidence\":0}.\n")
        append("Never invent names, numbers or message text. Copy them from the user's words.\n")
        append(if (bengali) "The command is usually in Bengali." else "The command may be in Bengali or English.")
    }

    /** Messages for the model call (kept here so the prompt and the utterance stay together). */
    fun messages(utterance: String, bengali: Boolean): List<com.voicedock.ai.ai.ChatMessage> =
        listOf(com.voicedock.ai.ai.ChatMessage.user(utterance.take(1000)))

    // ---------------------------------------------------------------- helpers

    private fun containsAny(low: String, vararg keys: String) = keys.any { low.contains(it) }

    /**
     * True when a fragment has at least one letter or digit of its own. A lone Bengali vowel sign
     * (U+09CB, a combining mark) is not a name, and `java.lang.Character` agrees: it is neither a letter
     * nor a digit.
     */
    fun meaningful(s: String): Boolean = s.any { it.isLetterOrDigit() }

    private fun afterAny(text: String, low: String, vararg keys: String): String {
        var best = -1
        var keyLen = 0
        for (k in keys) {
            val i = low.indexOf(k)
            if (i >= 0 && (best < 0 || i < best)) { best = i; keyLen = k.length }
        }
        return if (best < 0) text else text.substring(best + keyLen).trim()
    }

    /** Text after an absolute index in [low] (which is length-aligned with [text]). */
    private fun afterAnyFrom(text: String, low: String, index: Int): String {
        // [index] is already "just past the verb", so start there (do not skip a character).
        var start = index.coerceIn(0, text.length)
        // skip a single space so "বলো আমি" → "আমি"
        while (start < text.length && text[start] == ' ') start++
        return text.substring(start.coerceAtMost(text.length)).trim()
    }

    /** Text BEFORE the first matching trigger — Bangla commands put the object first. */
    private fun beforeAny(text: String, low: String, vararg keys: String): String {
        var best = -1
        for (k in keys) {
            val i = low.indexOf(k)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return if (best <= 0) "" else text.substring(0, best).trim()
    }

    /** "হোয়াটসঅ্যাপ অ্যাপটা" → "হোয়াটসঅ্যাপ"; quotes and filler words removed. */
    /** Removes the search scaffolding so the query is only what the user wants to know about. */
    fun searchQuery(q: String): String {
        var s = q
        listOf("গুগলে", "গুগল", "google", "ইউটিউবে", "youtube", "সার্চ", "search", "খোঁজো", "খুঁজে", "দাও", "করো", "কর", "for", "the web", "internet", "একটু")
            .forEach { key -> s = s.replace(key, " ", ignoreCase = true) }
        return s.replace(Regex("\\s+"), " ").trim().trim('।', ',', '?', '!', ' ')
    }

    fun cleanAppQuery(q: String): String = normalize(q)
        .replace(Regex("(?i)\\b(the|app|application|টা|টি|অ্যাপটা|অ্যাপটি|অ্যাপ|please|একটু|দয়া করে)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
