package com.voicedock.ai.send

/**
 * v1.3.3 — the accessibility pass, part one: **sending a message without a live notification.**
 *
 * Until now a message could only be sent for real when the app offered a Direct Reply action on its
 * notification (v0.8) and, from v1.2.1, when a scheduled message could open the chat pre-filled. This file is the
 * third route: type into the chat that is **already open** and press the app's own send button through
 * accessibility.
 *
 * It is deliberately conservative, because this is the one feature where a mistake is visible to another person:
 *
 *  * the app under the bubble must own the field (`expectedPackage`) — never "whatever is in front";
 *  * the field must look like a message box, and a send button must actually exist and be found;
 *  * nothing is auto-sent: a spoken command or a scheduled job does this, and the result is always reported as
 *    the enum below, in words, never as "sent" unless the field really became empty.
 *
 * The decisions are pure ([SendCore]); only [AccessibilitySender] touches an `AccessibilityNodeInfo`.
 */
enum class SendResult(val bn: String, val en: String) {
    SENT("পাঠানো হয়েছে ✓", "Sent ✓"),
    NO_SERVICE("Accessibility চালু নেই — চ্যাট খুলে নিজে পাঠান", "Accessibility is off — open the chat and send it yourself"),
    NO_FIELD("চ্যাটের লেখার ঘর পাওয়া যায়নি — চ্যাটটা খুলে রাখুন", "No message box found — keep the chat open"),
    NO_SEND_BUTTON("‘পাঠান’ বাটন পাওয়া যায়নি — নিজে পাঠান", "The send button was not found — send it yourself"),
    WRONG_APP("সামনের অ্যাপটি সেই চ্যাট নয় — চ্যাটটা খুলে আবার বলুন", "The app in front is not that chat — open it and ask again"),
    SECURE_FIELD("সুরক্ষিত/পাসওয়ার্ড ঘরে লেখা যায় না", "A secure/password field is never filled"),
    REJECTED("অ্যাপটি লেখা নেয়নি — নিজে পাঠান", "The app did not accept the text — send it yourself"),
}

data class SendScreen(
    /** Package of the app that owns the focused window. */
    val pkg: String,
    /** Human label, used in the answer. */
    val appLabel: String = "",
    /** An editable, non-password field that looks like a message box was found. */
    val hasMessageField: Boolean = false,
    /** Labels (contentDescription/text) of the candidate send buttons that were found. */
    val sendLabels: List<String> = emptyList(),
)

/** What the sender will do, decided before anything is typed. */
sealed class SendPlan {
    data object Proceed : SendPlan()
    data class Refuse(val result: SendResult) : SendPlan()
}

object SendCore {

    /**
     * Send-button labels in the languages these apps actually ship. Deliberately anchored: "send" must be the
     * whole label (or start the label), so a "Send location" menu item is not mistaken for the button.
     */
    private val sendWords = listOf(
        "send", "পাঠান", "পাঠাও", "পাঠিয়ে দাও", "enviar", "invia", "senden", "envoyer", "versturen",
        "إرسال", "发送", "送信", "보내기", "отправить", "gönder", "kirim", "gửi",
    )

    /** Only a few words may follow "send" and still mean the button ("Send now"), never "Send location". */
    private val sendSuffixes = listOf("now", "message", "reply", "বার্তা", "এখন")

    fun isSendLabel(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase() ?: return false
        if (t.isEmpty() || t.length > 24) return false
        if (sendWords.any { t == it }) return true
        return sendWords.any { w -> t.startsWith("$w ") && t.removePrefix("$w ").trim() in sendSuffixes }
    }

    /** Words that make a text field a *message* field rather than a search box. */
    private val messageHints = listOf(
        "message", "type a message", "বার্তা", "মেসেজ", "লিখুন", "メッセージ", "nachricht", "mensaje",
        "envoyer un message", "сообщение", "رسالة", "消息",
    )
    private val searchHints = listOf("search", "খুঁজুন", "খোঁজো", "search or type", "rechercher", "buscar")

    /**
     * Is this editable field plausibly the chat's composer? True when the hint/id says "message", and never when
     * it says "search" — typing a message into a search box is worse than refusing.
     */
    fun looksLikeMessageField(hint: String?, viewId: String?): Boolean {
        val all = listOfNotNull(hint, viewId).joinToString(" ").lowercase()
        if (all.isBlank()) return false
        if (searchHints.any { all.contains(it) }) return false
        return messageHints.any { all.contains(it) } || all.contains("input") || all.contains("composer") || all.contains("entry")
    }

    /**
     * The one decision that must not be wrong.
     *
     * @param expectedPackage the chat app the text is meant for; `null` means the caller does not know it, and
     *        then the screen must prove itself twice (message-looking field **and** a send button).
     */
    fun plan(screen: SendScreen, expectedPackage: String?): SendPlan = when {
        screen.pkg.isBlank() -> SendPlan.Refuse(SendResult.NO_FIELD)
        expectedPackage != null && screen.pkg != expectedPackage -> SendPlan.Refuse(SendResult.WRONG_APP)
        !screen.hasMessageField -> SendPlan.Refuse(SendResult.NO_FIELD)
        screen.sendLabels.isEmpty() -> SendPlan.Refuse(SendResult.NO_SEND_BUTTON)
        else -> SendPlan.Proceed
    }

    /** The honest sentence after the attempt. */
    fun resultText(result: SendResult, bengali: Boolean, target: String = ""): String {
        val base = if (bengali) result.bn else result.en
        return if (result == SendResult.SENT && target.isNotBlank()) {
            if (bengali) "‘$target’ কে $base" else "$base to ‘$target’"
        } else base
    }

    /** Shown when the accessibility route is used, so the user knows what is being done. */
    fun planNote(bengali: Boolean, appLabel: String): String = if (bengali) {
        "${appLabel.ifBlank { "চ্যাট" }}-এর খোলা চ্যাটে লেখা বসিয়ে ‘পাঠান’ চাপা হবে"
    } else {
        "will type into the open ${appLabel.ifBlank { "chat" }} and press its Send button"
    }

    fun helpText(bengali: Boolean): String = if (bengali) {
        "নোটিফিকেশনে সরাসরি উত্তরের সুবিধা না থাকলে: চ্যাটটা খুলে রাখুন (লেখার ঘর দেখা যাচ্ছে), " +
            "তারপর ‘রহিমকে বলো …’ বলুন — অ্যাপ Accessibility দিয়ে লিখে পাঠানোর চেষ্টা করবে, " +
            "আর না পারলে ঠিক কী আটকাল তা বলবে।"
    } else {
        "When the notification offers no direct reply: keep the chat open (with its message box visible), then say " +
            "“tell Rahim …” — the app types through accessibility and presses Send, or says exactly what stopped it."
    }
}
