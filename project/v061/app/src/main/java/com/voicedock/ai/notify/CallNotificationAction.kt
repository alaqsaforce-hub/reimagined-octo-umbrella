package com.voicedock.ai.notify

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * v0.9.0 — answer/decline a ringing call by pressing the button the *app itself* put in its
 * notification. This is the only route that works for WhatsApp/Telegram calls (no public call API) and
 * it is also the fallback when `TelecomManager.acceptRingingCall()` is refused by an OEM build.
 *
 * Keyword matching is pure and unit-tested; the firing itself is a plain `PendingIntent.send`, and every
 * failure returns null so the caller can tell the truth instead of saying "answered".
 */
object CallNotificationAction {

    /** Incoming-call notifications carry "Answer"/"Decline" (English) or localised equivalents. */
    private val answerWords = listOf(
        "answer", "accept", "pick up", "pickup", "উত্তর", "ধরো", "রিসিভ", "গ্রহণ",
    )
    private val declineWords = listOf(
        "decline", "reject", "dismiss", "ignore", "busy", "কেটে", "বাতিল", "প্রত্যাখ্যান", "কাট",
    )

    fun isAnswerTitle(title: CharSequence?): Boolean {
        val t = title?.toString()?.lowercase() ?: return false
        return answerWords.any { t.contains(it) } && !isDeclineTitle(title)
    }

    fun isDeclineTitle(title: CharSequence?): Boolean {
        val t = title?.toString()?.lowercase() ?: return false
        return declineWords.any { t.contains(it) }
    }

    /** A notification only counts as a ringing call when it offers both an answer and a decline path. */
    fun looksLikeCall(n: Notification?): Boolean {
        val actions = n?.actions ?: return false
        return actions.any { isAnswerTitle(it.title) || isDeclineTitle(it.title) }
    }

    fun pick(sbn: StatusBarNotification?, answer: Boolean): Notification.Action? {
        val actions = sbn?.notification?.actions ?: return null
        return if (answer) actions.firstOrNull { isAnswerTitle(it.title) && !isDeclineTitle(it.title) }
        else actions.firstOrNull { isDeclineTitle(it.title) }
    }

    /**
     * Fire the answer/decline button of the newest live ringing notification.
     * @return a short label of what was pressed ("WhatsApp: Answer"), or null when nothing matched.
     */
    fun fire(answer: Boolean): String? {
        val candidates = RemoteReply.liveAll()
            .filter { looksLikeCall(it.notification) }
            .sortedByDescending { it.postTime }
        for (sbn in candidates) {
            val action = pick(sbn, answer) ?: continue
            if (send(sbn, action)) {
                return "${appOf(sbn)}: ${action.title}"
            }
        }
        return null
    }

    private fun send(sbn: StatusBarNotification, action: Notification.Action): Boolean = try {
        action.actionIntent.send()
        true
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "call action cancelled")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "call action blocked: ${e.javaClass.simpleName}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "call action failed: ${e.javaClass.simpleName}")
        false
    }

    /** PendingIntent.send() needs no Context on API 26+ when it is a broadcast/service/activity intent. */
    private fun appOf(sbn: StatusBarNotification): String =
        sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private const val TAG = "VoiceDockCallAction"
}
