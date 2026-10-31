package com.voicedock.ai.events

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.voicedock.ai.notify.NotifStore
import com.voicedock.ai.notify.NotificationCapture
import com.voicedock.ai.notify.RemoteReply
import com.voicedock.ai.settings.SettingsStore

/**
 * v0.8.0 — the real notification listener (v0.6 shipped this as a no-op scaffold).
 *
 * It does exactly two things, both only while the user keeps "Notification access" granted in Android
 * Settings AND the `assistantBackgroundEvents` switch on in VoiceDock:
 *
 *  1. keeps the last notification objects in memory so Direct Reply can use their RemoteInput;
 *  2. stores a capped, text-only record (`notifications.json`) for the "আজ কে কে মেসেজ দিয়েছে?" digest.
 *
 * No network, no upload, no history backfill (Android gives none): the log starts when access is granted.
 */
class VoiceDockNotificationListener : NotificationListenerService() {

    private val store by lazy { NotifStore(filesDir) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        // Adopt what is already on screen so the digest is not empty on the first day.
        runCatching { activeNotifications?.forEach { consider(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        consider(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { RemoteReply.forget(it.key) }
    }

    private fun consider(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!SettingsStore.state.value.assistantBackgroundEvents) return
        if (sbn.packageName == packageName) return
        // Remember the live object first: direct reply needs it even if storage is off.
        if (sbn.notification?.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true) {
            runCatching { RemoteReply.remember(sbn) }
        }
        val entry = NotificationCapture.toEntry(sbn, packageName, appLabel(sbn.packageName), System.currentTimeMillis())
            ?: return
        runCatching { store.upsert(entry) }.onFailure { Log.w(TAG, "store failed: ${it.javaClass.simpleName}") }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        RemoteReply.clear()
    }

    /** Exposed for the settings screen: is this listener actually bound right now? */
    companion object {
        private const val TAG = "VoiceDockNotif"
        @Volatile var connected: Boolean = false
            private set
    }
}
