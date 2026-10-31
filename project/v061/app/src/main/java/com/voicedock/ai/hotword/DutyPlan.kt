package com.voicedock.ai.hotword

/**
 * v1.1.0 — "always in agent mode, but cheap on the battery".
 *
 * An always-available microphone is the most expensive thing this app can do, so the listening rhythm is a
 * policy, not a constant. The recogniser cycle itself cannot be slowed down, but the *gap between cycles*
 * can — that gap is where the radio, the CPU and the audio input are idle.
 *
 * | Situation | Gap between listen cycles |
 * |---|---|
 * | Charging | ~0.3 s — no reason to save anything |
 * | Screen on | 0.5 s (0.7 s in lean mode) |
 * | Screen off + always-on + lean | **9 s** — "check every few seconds", roughly a tenth of the audio time |
 * | Screen off + always-on, lean off | 1.5 s |
 * | Screen off, always-on off | 5 s (only checking whether the screen came back) |
 *
 * The trade-off is honest: with the lean gap a wake word spoken in the silent gap is missed, and the
 * assistant answers up to ~9 s late. That is the price of "always on" without eating the battery, and the
 * settings screen says so instead of hiding it.
 */
data class Duty(val idleMs: Long, val labelBn: String, val labelEn: String)

object DutyPlanner {

    /** Minimum gap: the recogniser is restarted almost immediately. */
    const val CONTINUOUS_MS = 300L

    fun plan(screenOn: Boolean, charging: Boolean, alwaysOn: Boolean, lean: Boolean): Duty = when {
        charging -> Duty(
            CONTINUOUS_MS,
            "চার্জে আছে — পুরো সময় শুনছে (ব্যাটারি চিন্তা নেই)",
            "Charging — listening continuously (battery is not a concern)",
        )
        screenOn -> Duty(
            if (lean) 700L else 450L,
            if (lean) "স্ক্রিন জ্বলছে — প্রায় সবসময় শুনছে" else "স্ক্রিন জ্বলছে — পুরো সময় শুনছে",
            if (lean) "Screen on — listening almost continuously" else "Screen on — listening continuously",
        )
        alwaysOn && lean -> Duty(
            9_000L,
            "স্ক্রিন বন্ধ — ব্যাটারি বাঁচাতে থেমে থেমে শুনছে (প্রায় ১০ সেকেন্ডে একবার)",
            "Screen off — duty-cycled to save battery (roughly once every 10 s)",
        )
        alwaysOn -> Duty(
            1_500L,
            "স্ক্রিন বন্ধ — শুনছে (ব্যাটারি বেশি খরচ হবে)",
            "Screen off — listening (more battery)",
        )
        else -> Duty(
            5_000L,
            "স্ক্রিন বন্ধ — এখন শুনছে না",
            "Screen off — not listening now",
        )
    }

    /** Rough duty cycle in percent, for the settings line ("≈N% of the time the mic is open"). */
    fun dutyPercent(duty: Duty, listenMs: Long): Int {
        val total = (duty.idleMs + listenMs).coerceAtLeast(1)
        return ((listenMs * 100) / total).toInt().coerceIn(1, 100)
    }
}
