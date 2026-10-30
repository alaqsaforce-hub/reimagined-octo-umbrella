package com.voicedock.ai.device

import android.content.Context
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.security.ProbeAudit
import com.voicedock.ai.settings.SettingsStore

/**
 * The single Android-side entry point for an on-tap screen probe.
 *
 * It exists so every caller gets the same two guarantees:
 *  1. the **user's own blocklist** is passed into [ScreenProbe] before any node is traversed, and
 *  2. every attempt is counted in the local [ProbeAudit] (counts only — no package, no text).
 *
 * Call sites must NOT call `ScreenProbe.probe` directly any more.
 */
object ProbeRunner {

    fun probe(
        svc: InsertAccessibilityService?,
        context: Context?,
        now: Long = System.currentTimeMillis(),
        maxChars: Int = 4000,
        screenshotBlocked: Boolean = false,
    ): ContextSnapshot {
        val blocked = SettingsStore.state.value.blockedPackages()
        val snapshot = ScreenProbe.probe(
            svc = svc,
            now = now,
            maxChars = maxChars,
            screenshotBlocked = screenshotBlocked,
            extraBlocked = blocked,
        )
        ProbeAudit.record(context, refused = snapshot.refusedForAudit(), now = now)
        return snapshot
    }
}

/**
 * True when the screen content was NOT used, whatever the reason: a failed probe, VoiceDock's own
 * window, the user blocklist, a reported secure window, a password field, or secret-like text.
 */
internal fun ContextSnapshot.refusedForAudit(): Boolean =
    !probeOk || isSelf || userBlocked || flagSecure || hasPasswordField || secureTextHits > 0
