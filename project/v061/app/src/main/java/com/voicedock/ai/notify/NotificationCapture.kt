package com.voicedock.ai.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification

/** Is "Notification access" granted to VoiceDock right now, and how to ask for it. */
object NotificationAccess {

    fun isGranted(ctx: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return false
        return flat.split(':').any { part ->
            runCatching { ComponentName.unflattenFromString(part)?.packageName }.getOrNull() == ctx.packageName
        }
    }

    fun openSettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        runCatching {
            ctx.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}

/**
 * v0.8.0 — turn one posted notification into a [NotifEntry].
 *
 * Respects the app's own privacy choices: an app that hides its previews gives us a title (who) and no
 * text, which is exactly what the digest will then report. Group summaries and our own notifications are
 * skipped; ongoing/foreground-service notifications (music players, VOIP "ongoing call") are kept but
 * marked so they never inflate a "today's messages" count.
 */
object NotificationCapture {

    /** Packages whose notifications are never about a person messaging you. */
    private val noisy = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.google.android.gms",
    )

    fun toEntry(sbn: StatusBarNotification?, selfPkg: String, appLabel: String, now: Long): NotifEntry? {
        if (sbn == null) return null
        if (sbn.packageName == selfPkg) return null
        if (sbn.packageName in noisy) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val e = n.extras ?: return null

        val sender = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString())
            ?.trim().orEmpty()
        if (sender.isBlank()) return null

        val text = (e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            ?.trim().orEmpty()

        val canReply = RemoteReply.supportsReply(n)
        return NotifEntry(
            key = sbn.key,
            pkg = sbn.packageName,
            appLabel = appLabel,
            sender = sender.take(80),
            text = text.take(400),
            at = now,
            canReply = canReply,
            summary = false,
        )
    }
}
