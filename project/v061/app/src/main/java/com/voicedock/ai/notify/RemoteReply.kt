package com.voicedock.ai.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * v0.8.0 — send a reply into WhatsApp (or any app) without opening it, using the notification's own
 * Direct Reply action (`RemoteInput` + its `PendingIntent`).
 *
 * This is the only route that exists for a non-rooted app: there is no WhatsApp API. It therefore fails
 * in exactly the cases where the app did not offer a reply action (no RemoteInput, notification
 * dismissed, or previews disabled) — and every failure is reported, never swallowed.
 */
object RemoteReply {

    /** Live notifications, newest last, kept in memory only (a PendingIntent cannot be serialised). */
    private const val MAX = 30
    private val live = LinkedHashMap<String, StatusBarNotification>()

    @Synchronized
    fun remember(sbn: StatusBarNotification) {
        live.remove(sbn.key)
        live[sbn.key] = sbn
        while (live.size > MAX) {
            val oldest = live.keys.firstOrNull() ?: break
            live.remove(oldest)
        }
    }

    @Synchronized
    fun forget(key: String) { live.remove(key) }

    @Synchronized
    fun clear() = live.clear()

    @Synchronized
    fun get(key: String): StatusBarNotification? = live[key]

    /** Snapshot of the live notifications (newest last) — used by the call-answer fallback. */
    @Synchronized
    fun liveAll(): List<StatusBarNotification> = live.values.toList()

    /** Newest live notification from [pkg] whose sender matches, preferring ones that can reply. */
    @Synchronized
    fun find(sender: String, pkg: String? = null): StatusBarNotification? {
        val candidates = live.values
            .filter { pkg == null || it.packageName == pkg }
            .filter { replyActionOf(it.notification) != null }
        if (candidates.isEmpty()) return null
        val byName = candidates.filter { sbn ->
            val matches = SenderMatch.resolve(sender, listOf(NotifText.senderOf(sbn)))
            matches.isNotEmpty()
        }
        return (byName.ifEmpty { candidates }).maxByOrNull { it.postTime }
    }

    /** The action that carries a free-text RemoteInput, if any. */
    fun replyActionOf(n: Notification?): Notification.Action? {
        val actions = n?.actions ?: return null
        // Prefer an action that is explicitly marked as a reply, else the first with a RemoteInput.
        val withInput = actions.filter { a -> !a.remoteInputs.isNullOrEmpty() }
        // semanticAction is API 28+; below that the first RemoteInput action is the reply one anyway.
        if (Build.VERSION.SDK_INT >= 28) {
            withInput.firstOrNull { a -> a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
                ?.let { return it }
        }
        return withInput.firstOrNull()
    }

    fun supportsReply(n: Notification?): Boolean = replyActionOf(n) != null

    /**
     * @return null on success, or a short reason code: "no_notification", "no_action", "blocked"
     */
    fun send(ctx: Context, sbn: StatusBarNotification?, text: String): String? {
        if (sbn == null) return "no_notification"
        val action = replyActionOf(sbn.notification) ?: return "no_action"
        val inputs = action.remoteInputs ?: return "no_action"
        if (text.isBlank()) return "blocked"
        val intent = Intent()
        val results = android.os.Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            sendPending(ctx, action.actionIntent, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "reply intent cancelled")
            "no_action"
        } catch (e: SecurityException) {
            Log.w(TAG, "reply blocked: ${e.javaClass.simpleName}")
            "blocked"
        } catch (e: Exception) {
            Log.w(TAG, "reply failed: ${e.javaClass.simpleName}")
            "blocked"
        }
    }

    private fun sendPending(ctx: Context, pi: PendingIntent, fillIn: Intent) {
        // minSdk is 26, so PendingIntent.send(context, code, intent) is always available.
        pi.send(ctx, 0, fillIn)
    }

    private const val TAG = "VoiceDockReply"
}

/** Small extraction helpers shared by the listener and the reply finder (no Android deps beyond Notification). */
internal object NotifText {
    fun senderOf(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras
        val conversation = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        if (!conversation.isNullOrBlank()) return conversation
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (!title.isNullOrBlank()) return title
        return e.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()
    }
}
