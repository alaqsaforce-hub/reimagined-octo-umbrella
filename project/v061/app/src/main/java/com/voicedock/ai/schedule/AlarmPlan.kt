package com.voicedock.ai.schedule

/**
 * v1.2.0 — exact alarm or an honest approximation?
 *
 * Android 12+ only allows `setExactAndAllowWhileIdle` with the SCHEDULE_EXACT_ALARM special access, which
 * the user grants in system settings. Two bad options are common: crash/throw when the access is missing,
 * or silently schedule with `set()` and hope. This decides in advance, in pure code, and the caller reports
 * the truth ("মিনিটে-মিনিটে" vs "কয়েক মিনিট দেরি হতে পারে").
 */
data class AlarmPlan(
    val triggerAt: Long,
    val exact: Boolean,
    /** Widest lateness the user should be told about, in minutes (0 = to the minute). */
    val lateByMinutes: Int,
)

object AlarmPlanner {

    /** An alarm this close is not worth the exact path (it would fire before the system registers it). */
    const val MIN_LEAD_MS = 2_000L

    /** A reminder in the past by this much is still worth one notification (the phone was probably asleep). */
    const val GRACE_MS = 60_000L

    /** null = nothing to do (the moment is long gone). */
    fun plan(now: Long, at: Long, exactAllowed: Boolean): AlarmPlan? {
        if (at < now - GRACE_MS) return null
        val trigger = maxOf(at, now + MIN_LEAD_MS)
        val exact = exactAllowed && trigger - now >= MIN_LEAD_MS
        return AlarmPlan(trigger, exact, if (exact) 0 else 15)
    }

    /** Snooze from [now] — the alarm time itself is irrelevant once it has fired. */
    fun snooze(now: Long, minutes: Int = 10): Long = now + minutes.coerceIn(1, 120) * 60_000L

    /** Which of the two sentences the UI/answer uses. */
    fun note(plan: AlarmPlan, bengali: Boolean): String = when {
        plan.exact -> if (bengali) "ঠিক সময়ে বলবে" else "it will be on time"
        else -> if (bengali) "‘ঠিক সময়ে রিমাইন্ডার’ অনুমতি নেই, তাই কয়েক মিনিট দেরি হতে পারে — সেটিংস → সিস্টেম ও এজেন্ট থেকে দিতে পারেন"
        else "no exact-alarm access, so it may be a few minutes late — grant it in Settings → System & agent"
    }
}
