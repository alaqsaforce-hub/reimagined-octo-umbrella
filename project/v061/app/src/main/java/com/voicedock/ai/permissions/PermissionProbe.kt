package com.voicedock.ai.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.media.MediaControl
import com.voicedock.ai.notify.NotificationAccess

/**
 * v1.2.0 — the Android half of the permission catalog: "is this access really held right now?".
 *
 * Deliberately a real probe per item, never a stored flag: a listener can be *listed* as enabled and still
 * not be connected, a role can be held by another assistant, and the exact-alarm access can be revoked later
 * in system settings. Where the OS offers no cheap check, the item is reported as "granted by install" and
 * the Settings row does a deeper functional test instead.
 */
object PermissionProbe {

    fun granted(ctx: Context, item: PermissionItem): Boolean = grantedRaw(ctx, item)

    private fun grantedRaw(ctx: Context, item: PermissionItem): Boolean = when (item.key) {
        PermissionCatalog.ACCESSIBILITY -> Permissions.accessibility(ctx)
        PermissionCatalog.NOTIFICATION_LISTENER -> NotificationAccess.isGranted(ctx)
        PermissionCatalog.ASSISTANT_ROLE -> assistantRole(ctx)
        PermissionCatalog.items.first { it.permission == "android.permission.SYSTEM_ALERT_WINDOW" }.key -> Settings.canDrawOverlays(ctx)
        PermissionCatalog.items.first { it.permission == "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" }.key ->
            runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) == true }
                .getOrDefault(false)
        PermissionCatalog.items.first { it.permission == "android.permission.SCHEDULE_EXACT_ALARM" }.key -> exactAlarm(ctx)
        PermissionCatalog.items.first { it.permission == "android.permission.RECEIVE_BOOT_COMPLETED" }.key -> true
        else -> item.permission?.let { ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } ?: false
    }

    fun assistantRole(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) false
        else (ctx.getSystemService(Context.ROLE_SERVICE) as? RoleManager)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)

    fun exactAlarm(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 31) true
        else (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    /** Where the user grants it. Runtime permissions use the in-app dialog instead (see [requestable]). */
    fun settingsIntent(ctx: Context, item: PermissionItem): Intent? = when (item.key) {
        PermissionCatalog.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionCatalog.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PermissionCatalog.ASSISTANT_ROLE ->
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) else null
        else -> when (item.permission) {
            "android.permission.SYSTEM_ALERT_WINDOW" ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            "android.permission.SCHEDULE_EXACT_ALARM" ->
                if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")) else null
            else -> null
        }
    }

    /**
     * A functional check where the OS flag alone can lie. Returns a ✓/✗ sentence for the Settings row.
     * The default is honest: it reports the state and names the feature that is blocked, instead of
     * claiming a capability was exercised when it was not.
     */
    fun test(ctx: Context, item: PermissionItem, bengali: Boolean): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        val held = grantedRaw(ctx, item)
        return when (item.key) {
            PermissionCatalog.ACCESSIBILITY ->
                if (InsertAccessibilityService.instance != null) bn("✓ সার্ভিস সংযুক্ত — স্ক্রিন পড়া যাচ্ছে", "✓ service connected — the screen can be read")
                else bn("✗ চালু থাকলেও সংযুক্ত নয় — বন্ধ করে আবার চালু করুন", "✗ enabled but not connected — toggle it off and on")
            PermissionCatalog.NOTIFICATION_LISTENER -> {
                val sessions = MediaControl.sessionCount(ctx) // -1 = the OS refused the probe
                if (!held) bn("✗ নোটিফিকেশন অ্যাক্সেস নেই", "✗ notification access is off")
                else if (sessions < 0) bn("✓ অনুমতি আছে (এই মুহূর্তে কোনো মিডিয়া সেশন নেই)", "✓ granted (no media session active right now)")
                else bn("✓ কাজ করছে — ${com.voicedock.ai.schedule.TimeWords.bn(sessions.toString())} টি মিডিয়া সেশন দেখা যাচ্ছে", "✓ working — $sessions media session(s) visible")
            }
            PermissionCatalog.ASSISTANT_ROLE ->
                if (held) bn("✓ এই অ্যাপই ডিফল্ট সহকারী", "✓ this app is the default assistant")
                else bn("✗ সহকারী রোল নেই — হোম বাটন চেপে ধরে খোলা যাবে না", "✗ the assistant role is not held — the home gesture will not open it")
            else ->
                if (held) bn("✓ অনুমতি আছে — ${item.whyBn}", "✓ granted — ${item.whyEn}")
                else bn("✗ অনুমতি নেই — ${item.whyBn} বন্ধ আছে", "✗ not granted — ${item.whyEn} is off")
        }
    }
}
