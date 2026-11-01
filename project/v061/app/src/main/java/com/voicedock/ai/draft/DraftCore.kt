package com.voicedock.ai.draft

/**
 * v1.3.1 — the draft half of "voice-to-voice": the assistant composes what was said into a message, **shows**
 * it, and puts it into the chat box only when the user asks. Nothing auto-inserts — that is a standing rule of
 * this project, so it is encoded here as a constant rather than left to each call site.
 *
 * Pure Kotlin on purpose: the "can this be inserted, and what will the user be told" decision must be the same
 * whether it runs behind the floating bubble, inside the voice screen or from a spoken "ঢোকাও".
 */
enum class DraftRoute {
    /** Accessibility can type into the focused field of the app underneath. */
    ACCESSIBILITY,

    /** No accessibility service: the text goes to the clipboard and the user pastes it. */
    CLIPBOARD,
}

data class DraftPlan(
    val text: String,
    /** Where it will land, as shown to the user ("WhatsApp Business", "" = the app in front). */
    val targetLabel: String,
    val route: DraftRoute,
    val noteBn: String,
    val noteEn: String,
) {
    /** Never true — kept explicit so a future edit cannot quietly start auto-inserting. */
    val requiresExplicitInsert: Boolean get() = true
}

object DraftCore {

    /** Longest draft the bubble will offer to insert — beyond this the user should edit it in the app. */
    const val MAX_DRAFT = 1_500

    private val wakeWords = listOf("শাহরুখ", "shahrukh", "ভয়েসডক", "voicedock")

    /**
     * Turns raw recognition output into a presentable draft: a leading wake word is dropped, runs of
     * whitespace/newlines are collapsed, and the text is trimmed. Nothing else is rewritten — the words
     * stay the user's own.
     */
    fun tidy(spoken: String): String {
        var t = spoken.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        for (w in wakeWords) {
            val low = t.lowercase()
            val at = low.indexOf(w)
            if (at == 0) t = t.substring(w.length).trim().trim(',', '।', '.', ' ', '—', '-').trim()
        }
        return if (t.length > MAX_DRAFT) t.take(MAX_DRAFT) else t
    }

    /**
     * @param spoken what the recogniser produced
     * @param hasAccessibility is [com.voicedock.ai.insertion.InsertAccessibilityService] connected right now
     * @param targetLabel the app the user was in, when it is known — never guessed
     */
    fun plan(spoken: String, hasAccessibility: Boolean, targetLabel: String = ""): DraftPlan {
        val text = tidy(spoken)
        val where = targetLabel.trim()
        return if (hasAccessibility) {
            DraftPlan(
                text = text,
                targetLabel = where,
                route = DraftRoute.ACCESSIBILITY,
                noteBn = if (where.isBlank()) "কার্সর যে ঘরে আছে সেখানে বসবে" else "$where-এ বসবে",
                noteEn = if (where.isBlank()) "goes into the field with the cursor" else "goes into $where",
            )
        } else {
            DraftPlan(
                text = text,
                targetLabel = where,
                route = DraftRoute.CLIPBOARD,
                noteBn = "Accessibility চালু নেই — লেখাটা কপি হবে, আপনি পেস্ট করুন",
                noteEn = "Accessibility is off — the text is copied, paste it yourself",
            )
        }
    }

    /** True when the draft card is worth showing at all. */
    fun presentable(draft: String): Boolean = draft.trim().length >= 2

    // ---- spoken control of the card -------------------------------------------------------------

    /** "ঢোকাও" / "বসাও" / "insert" / "paste it" — the user asking for the draft to be placed. */
    fun isInsertCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        // A sentence that merely contains the word is a different request ("ওখানে বসাও না, পরে").
        return listOf("ঢোকাও", "ঢুকিয়ে দাও", "বসাও", "বসিয়ে দাও", "লিখে দাও", "পেস্ট करो", "পেস্ট করো",
            "insert", "insert it", "paste it", "paste").any { t == it || t.startsWith("$it ") || t.endsWith(" $it") }
    }

    /** "খসড়া বাতিল" / "discard" / "মুছে ফেলো" — throw the draft away. */
    fun isDiscardCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return listOf("খসড়া বাতিল", "খসড়াটা বাতিল", "ড্রাফট বাতিল", "খসড়া মুছে ফেলো", "খসড়া ফেলে দাও",
            "discard draft", "discard it", "discard", "delete the draft", "throw it away").any { t.contains(it) }
    }

    /** "খসড়া রাখো" — keep it as a saved draft and close the card. */
    fun isKeepCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return listOf("খসড়া রাখো", "খসড়াটা রাখো", "ড্রাফটে রাখো", "save the draft", "keep the draft", "keep it")
            .any { t.contains(it) }
    }

    // ---- labels ---------------------------------------------------------------------------------

    fun title(bengali: Boolean): String = if (bengali) "খসড়া প্রস্তুত" else "Draft ready"

    fun insertLabel(bengali: Boolean): String = if (bengali) "ঢোকাও" else "Insert"

    fun keepLabel(bengali: Boolean): String = if (bengali) "রাখো" else "Keep"

    fun discardLabel(bengali: Boolean): String = if (bengali) "বাতিল" else "Discard"

    fun insertDone(bengali: Boolean, where: String): String = if (bengali) {
        if (where.isBlank()) "খসড়া বসানো হয়েছে ✓" else "$where-এ খসড়া বসানো হয়েছে ✓"
    } else {
        if (where.isBlank()) "Draft inserted ✓" else "Draft inserted into $where ✓"
    }

    fun keptText(bengali: Boolean): String =
        if (bengali) "খসড়া সেভ করা আছে — ড্রাফট ইতিহাসে পাবেন" else "Draft saved — see Draft history"

    fun nothingText(bengali: Boolean): String =
        if (bengali) "এখন কোনো খসড়া নেই — আগে বলুন, লেখা তৈরি হবে" else "There is no draft right now — speak first"

    // ---- the voice screen's own copy -------------------------------------------------------------

    fun voiceTitle(bengali: Boolean): String = if (bengali) "ভয়েস মোড" else "Voice mode"

    fun voiceHint(bengali: Boolean): String = if (bengali) {
        "বলুন — লেখা নিচে আসবে। ‘⚡’ চাপলে সেটা কমান্ড হিসেবে চলবে, ‘ঢোকাও’ চাপলে অন্য অ্যাপে বসবে।"
    } else {
        "Speak — the words appear below. ‘⚡’ runs them as a command, ‘Insert’ puts them into the other app."
    }

    fun voiceEmpty(bengali: Boolean): String = if (bengali) {
        "এখনো কিছু বলা হয়নি। মাইকে চাপ দিয়ে বলুন।"
    } else {
        "Nothing said yet. Tap the microphone and speak."
    }
}
