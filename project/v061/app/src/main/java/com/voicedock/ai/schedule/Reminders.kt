package com.voicedock.ai.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.voicedock.ai.R
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.data.database.ReminderRow
import com.voicedock.ai.data.database.ReminderStatus
import com.voicedock.ai.settings.tr
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.2.0 — reminders: the first kind of scheduled job.
 *
 * A reminder is a Room row + an `AlarmManager` alarm. The row is the truth (it survives reboots, which are
 * re-armed by [BootReceiver]); the alarm is only a wake-up. Everything the user hears is derived from the
 * row, so a missed alarm shows up in Settings instead of vanishing.
 */
object Reminders {

    /** Receivers must finish fast; the DB work happens here and `goAsync()` is closed afterwards. */
    internal val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val ACTION_FIRE = "com.voicedock.ai.reminder.FIRE"
    const val ACTION_DONE = "com.voicedock.ai.reminder.DONE"
    const val ACTION_SNOOZE = "com.voicedock.ai.reminder.SNOOZE"
    const val ACTION_CANCEL = "com.voicedock.ai.reminder.CANCEL"
    const val EXTRA_ID = "id"
    private const val CHANNEL = "reminders"

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** Exact alarms need a special access on Android 12+; without it the alarm is "a few minutes late". */
    fun exactAllowed(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 31) true
        else (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    /** Opens the system screen where "alarms & reminders" is allowed for this app (Android 12+ only). */
    fun requestExactAlarmAccess(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    suspend fun add(ctx: Context, text: String, atMillis: Long, source: String = "voice"): ReminderRow {
        val row = ReminderRow(
            id = newId(),
            text = text.trim(),
            triggerAt = atMillis,
            createdAt = System.currentTimeMillis(),
            status = ReminderStatus.PENDING,
            source = source,
        )
        VoiceDock.db(ctx).reminders().upsert(row)
        schedule(ctx, row)
        return row
    }

    suspend fun pending(ctx: Context): List<ReminderRow> = VoiceDock.db(ctx).reminders().pending()

    suspend fun cancel(ctx: Context, id: String) {
        VoiceDock.db(ctx).reminders().setStatus(id, ReminderStatus.CANCELLED)
        alarm(ctx, id)?.let { (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(it) }
        dismiss(ctx, id)
    }

    suspend fun finish(ctx: Context, id: String) {
        VoiceDock.db(ctx).reminders().setStatus(id, ReminderStatus.DONE)
        dismiss(ctx, id)
        prune(ctx)
    }

    suspend fun snooze(ctx: Context, id: String, minutes: Int = 10) {
        val dao = VoiceDock.db(ctx).reminders()
        val row = dao.get(id) ?: return
        val at = AlarmPlanner.snooze(System.currentTimeMillis(), minutes)
        val next = row.copy(triggerAt = at, status = ReminderStatus.PENDING)
        dao.upsert(next)
        schedule(ctx, next)
        dismiss(ctx, id)
    }

    /** Called after a reboot (alarms do not survive it) and after the exact-alarm access is granted. */
    suspend fun rescheduleAll(ctx: Context) {
        VoiceDock.db(ctx).reminders().pending().forEach { row ->
            val plan = AlarmPlanner.plan(System.currentTimeMillis(), row.triggerAt, exactAllowed(ctx))
            if (plan != null) schedule(ctx, row) else VoiceDock.db(ctx).reminders().setStatus(row.id, ReminderStatus.DONE)
        }
    }

    private suspend fun prune(ctx: Context) {
        runCatching { VoiceDock.db(ctx).reminders().prune(System.currentTimeMillis() - 7L * 24 * 3_600_000) }
    }

    /** Arms the alarm; a null plan (long past) marks the row done instead of silently keeping it. */
    fun schedule(ctx: Context, row: ReminderRow) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val plan = AlarmPlanner.plan(System.currentTimeMillis(), row.triggerAt, exactAllowed(ctx)) ?: return
        val pi = alarm(ctx, row.id) ?: return
        runCatching {
            if (plan.exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
        }
    }

    /** The PendingIntent for one reminder. Distinct ids keep alarms from overwriting each other. */
    fun alarm(ctx: Context, id: String): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            ctx,
            id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    // ---- text -----------------------------------------------------------------------------------

    /** "মনে করিয়ে দিচ্ছি: পানি খেতে" with the honest exact/inexact note. */
    fun confirmText(row: ReminderRow, bengali: Boolean, now: Long = System.currentTimeMillis(), exactAllowed: Boolean = true): String {
        val whenText = TimeWords.label(row.triggerAt, now, bengali)
        val body = if (bengali) "$whenText — মনে করিয়ে দেব: ${row.text}"
        else "$whenText — I will remind you: ${row.text}"
        val plan = AlarmPlanner.plan(now, row.triggerAt, exactAllowed)
        // Never claim "to the minute" when the exact-alarm access is missing.
        return if (plan?.exact == false) "$body\n(${AlarmPlanner.note(plan, bengali)})" else body
    }

    fun listText(rows: List<ReminderRow>, bengali: Boolean, now: Long = System.currentTimeMillis()): String {
        fun bn(b: String, e: String) = if (bengali) b else e
        if (rows.isEmpty()) return bn("এখন কোনো রিমাইন্ডার নেই।", "No reminders are set.")
        return buildString {
            append(bn("আসছে ${TimeWords.bn(rows.size.toString())} টি রিমাইন্ডার:", "${rows.size} upcoming reminder(s):"))
            rows.take(6).forEach { r ->
                append("\n• ${TimeWords.label(r.triggerAt, now, bengali)} — ${r.text}")
            }
            if (rows.size > 6) append(bn("\n…আরও ${TimeWords.bn((rows.size - 6).toString())} টি", "\n…and ${rows.size - 6} more"))
        }
    }

    // ---- notification ---------------------------------------------------------------------------

    fun notifyChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, tr("রিমাইন্ডার", "Reminders"), NotificationManager.IMPORTANCE_HIGH)
        ch.description = tr("সময়মতো মনে করিয়ে দেয়", "Speaks up when the time comes")
        nm.createNotificationChannel(ch)
    }

    fun post(ctx: Context, row: ReminderRow) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        notifyChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, com.voicedock.ai.ui.MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(action: String, label: String, code: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                ctx, row.id.hashCode() * 31 + code,
                Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, row.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action(0, label, pi)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(tr("⏰ রিমাইন্ডার", "⏰ Reminder"))
            .setContentText(row.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(row.text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(action(ACTION_DONE, tr("হয়ে গেছে ✓", "Done ✓"), 1))
            .addAction(action(ACTION_SNOOZE, tr("১০ মিনিট পরে", "In 10 minutes"), 2))
            .build()
        runCatching { nm.notify(notifId(row.id), n) }
    }

    fun dismiss(ctx: Context, id: String) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(notifId(id)) }
    }

    private fun notifId(id: String): Int = 20_000 + (id.hashCode() and 0x0FFF)
}

/** Fires a reminder, or handles the notification's Done / Snooze / Cancel buttons. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Reminders.io.launch {
            try {
                when (intent.action) {
                    Reminders.ACTION_DONE -> Reminders.finish(app, id)
                    Reminders.ACTION_SNOOZE -> Reminders.snooze(app, id, 10)
                    Reminders.ACTION_CANCEL -> Reminders.cancel(app, id)
                    Reminders.ACTION_FIRE -> {
                        val row = VoiceDock.db(app).reminders().get(id) ?: return@launch
                        if (row.status == ReminderStatus.PENDING) {
                            Reminders.post(app, row)
                            // Armed again: the row stays pending until the user marks it done, so a missed
                            // notification is still visible in Settings instead of disappearing.
                            VoiceDock.db(app).reminders().setStatus(id, ReminderStatus.DONE)
                        }
                    }
                }
            } catch (t: Throwable) {
                com.voicedock.ai.security.CrashLogStore(java.io.File(app.filesDir, "crash-reports")).saveThrowable(t)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms do not survive a reboot: re-arm every pending reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        Reminders.io.launch {
            try {
                Reminders.rescheduleAll(app)
            } catch (t: Throwable) {
                com.voicedock.ai.security.CrashLogStore(java.io.File(app.filesDir, "crash-reports")).saveThrowable(t)
            } finally {
                pending.finish()
            }
        }
    }
}
