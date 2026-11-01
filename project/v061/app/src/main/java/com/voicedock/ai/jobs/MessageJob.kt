package com.voicedock.ai.jobs

/**
 * v1.2.1 — "রাতে রহিমকে বলো আমি কাল আসব": a message that is sent later, while the phone is in a pocket.
 *
 * Android gives a third-party app exactly three ways to put text into someone's chat:
 *
 *  1. **the notification's own Direct Reply action** — truly silent, but only while that app's notification
 *     is still live at that moment;
 *  2. **opening the chat with the text pre-filled** (WhatsApp `wa.me/…`) — the user taps send;
 *  3. accessibility automation of the open chat — planned for v1.3, not this release.
 *
 * The app must not pretend (1) happened when it did (2) or nothing. [MessageJobCore] therefore decides the
 * channel *before* the moment arrives, the UI says which one will be used, and the fired notification says
 * exactly what happened. Pure Kotlin, fully unit-tested.
 */
enum class SendChannel {
    /** Silent: the app's own notification carries a Direct Reply action. */
    DIRECT_REPLY,

    /** Best-effort: the chat is opened with the text filled in; the user presses send. */
    OPEN_CHAT,

    /** Nothing can be delivered right now (no live notification, no number, no target app). */
    BLOCKED,
}

data class SendPlan(
    val channel: SendChannel,
    /** Honest sentence for the confirmation and for the fired notification. */
    val noteBn: String,
    val noteEn: String,
    /** true only for [SendChannel.DIRECT_REPLY] — the only path that sends without the user. */
    val sendsWithoutUser: Boolean,
)

object MessageJobCore {

    /**
     * @param liveReplyable the target app has a *live* notification with a Direct Reply action right now
     * @param canOpenChat   a chat app is installed and the contact resolves to a number (`wa.me`)
     */
    fun plan(liveReplyable: Boolean, canOpenChat: Boolean): SendPlan = when {
        liveReplyable -> SendPlan(
            SendChannel.DIRECT_REPLY,
            "নোটিফিকেশনের ভেতর দিয়েই চুপচাপ পাঠিয়ে দেব",
            "sent silently through the app's notification reply",
            sendsWithoutUser = true,
        )
        canOpenChat -> SendPlan(
            SendChannel.OPEN_CHAT,
            "চ্যাট খুলে লেখাটা বসিয়ে দেব — ‘পাঠাও’ চাপতে হবে",
            "the chat opens with the text filled in — you press send",
            sendsWithoutUser = false,
        )
        else -> SendPlan(
            SendChannel.BLOCKED,
            "এই মুহূর্তে পাঠানোর কোনো পথ নেই — সময় হলে জানাব, তারপর আপনি পাঠাবেন",
            "no delivery path right now — you will be notified at that time and can send it yourself",
            sendsWithoutUser = false,
        )
    }

    /** What the user sees at the moment the job fires. Never claims a send that did not happen. */
    fun firedText(target: String, body: String, channel: SendChannel, bengali: Boolean): String {
        val short = body.take(80)
        return when (channel) {
            SendChannel.DIRECT_REPLY -> if (bengali) "✓ ‘$target’ কে পাঠানো হয়েছে: $short"
            else "✓ sent to ‘$target’: $short"
            SendChannel.OPEN_CHAT -> if (bengali) "‘$target’ — চ্যাট খুলে দিলাম, ‘পাঠাও’ চাপুন: $short"
            else "‘$target’ — the chat is open, press send: $short"
            SendChannel.BLOCKED -> if (bengali) "‘$target’ কে পাঠানো যায়নি — নিজে পাঠান: $short"
            else "could not send to ‘$target’ — please send it yourself: $short"
        }
    }

    /** The schedule-time sentence, including the honest limit. */
    fun scheduledText(target: String, body: String, whenText: String, plan: SendPlan, bengali: Boolean): String =
        if (bengali) "$whenText — ‘$target’ কে পাঠাব: ${body.take(80)}\n(${plan.noteBn})"
        else "$whenText — I will send ‘$target’: ${body.take(80)}\n(${plan.noteEn})"

    /** WhatsApp's documented deep link; no permission and no API needed. */
    fun whatsappUrl(number: String, body: String): String {
        val digits = number.filter { it.isDigit() }
        val text = java.net.URLEncoder.encode(body, "UTF-8")
        return "https://wa.me/$digits?text=$text"
    }

    /**
     * A message job needs the target to still exist at fire time. Anything else is reported, not hidden.
     * Kept as a rule so the receiver and the tests agree.
     */
    fun canFire(kind: String, target: String, body: String): Boolean =
        kind == KIND_MESSAGE && target.isNotBlank() && body.isNotBlank()

    const val KIND_REMINDER = "reminder"
    const val KIND_MESSAGE = "message"
}
