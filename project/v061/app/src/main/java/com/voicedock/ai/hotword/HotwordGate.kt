package com.voicedock.ai.hotword

/**
 * v1.0.0 — when the microphone may be open for the wake word.
 *
 * The rule is deliberately conservative and pure, so it can be tested without a phone:
 *
 *  * the feature is **off** until the user turns it on (default `hotwordEnabled = false`);
 *  * by default it only listens while the **screen is on** — an always-on microphone is an explicit,
 *    second opt-in (`hotwordAlwaysOn`), because it is the single biggest battery/privacy decision in the app;
 *  * it never competes with dictation: while the dictation engine owns the microphone the wake listener is
 *    paused (Android allows one client at a time, and stealing the mic mid-sentence would truncate text).
 */
object HotwordGate {

    /** Phases in which VoiceDock itself is using the microphone. */
    fun dictationBusy(phaseName: String?): Boolean =
        phaseName in setOf("LISTENING", "TEMP_PAUSE", "PROCESSING", "PAUSED")

    fun shouldListen(
        enabled: Boolean,
        alwaysOn: Boolean,
        screenOn: Boolean,
        dictationBusy: Boolean,
    ): Boolean = enabled && !dictationBusy && (alwaysOn || screenOn)

    /** One line of honest status for the settings screen. */
    fun status(enabled: Boolean, alwaysOn: Boolean, screenOn: Boolean, bengali: Boolean): String = when {
        !enabled -> if (bengali) "বন্ধ — ‘শাহরুখ’ ডাকলে কেউ শুনবে না।" else "Off — saying ‘Shahrukh’ will do nothing."
        alwaysOn -> if (bengali)
            "চালু — স্ক্রিন বন্ধ থাকলেও শুনছে (ব্যাটারি বেশি খরচ হবে)।"
        else
            "On — listening even with the screen off (more battery)."
        screenOn -> if (bengali) "চালু — স্ক্রিন জ্বললেই শুনছে।" else "On — listening while the screen is on."
        else -> if (bengali)
            "স্ক্রিন বন্ধ — এখন শুনছে না (স্ক্রিন জ্বালালেই শুনবে)।"
        else
            "Screen off — paused until the screen comes back on."
    }

    /** How long to wait before trying the recogniser again, given the last outcome. */
    fun backoffMs(failures: Int): Long = (400L + failures * 600L).coerceIn(400L, 5_000L)
}
