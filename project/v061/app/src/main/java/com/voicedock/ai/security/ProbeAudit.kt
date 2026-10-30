package com.voicedock.ai.security

import android.content.Context

/**
 * Local, on-device audit counter for screen probes (open P0 item from the v0.6 handoff:
 * *"no verified audit counter"*).
 *
 * It records only **counts and a timestamp** — never a package name, app label, window title or
 * any screen text — so the counter itself cannot become a privacy leak. It lives in private
 * SharedPreferences, is never uploaded, and the user can reset it at any time.
 */
data class ProbeAuditSnapshot(
    /** Every probe attempt, including the ones that were refused. */
    val probes: Int = 0,
    /** Probes where screen content was NOT used (secret/OTP hit, password field, blocked app, no access). */
    val refused: Int = 0,
    /** Epoch millis of the last probe, 0 when none. */
    val lastAt: Long = 0L,
) {
    val allowed: Int get() = (probes - refused).coerceAtLeast(0)
}

object ProbeAudit {
    private const val FILE = "voicedock_probe_audit"
    private const val K_PROBES = "probes"
    private const val K_REFUSED = "refused"
    private const val K_LAST = "last_at"

    private fun prefs(context: Context?) =
        context?.applicationContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context?, refused: Boolean, now: Long = System.currentTimeMillis()) {
        val p = prefs(context) ?: return
        p.edit()
            .putInt(K_PROBES, p.getInt(K_PROBES, 0) + 1)
            .putInt(K_REFUSED, p.getInt(K_REFUSED, 0) + if (refused) 1 else 0)
            .putLong(K_LAST, now)
            .apply()
    }

    @Synchronized
    fun read(context: Context?): ProbeAuditSnapshot {
        val p = prefs(context) ?: return ProbeAuditSnapshot()
        return ProbeAuditSnapshot(
            probes = p.getInt(K_PROBES, 0),
            refused = p.getInt(K_REFUSED, 0),
            lastAt = p.getLong(K_LAST, 0L),
        )
    }

    @Synchronized
    fun reset(context: Context?) {
        prefs(context)?.edit()?.clear()?.apply()
    }
}
