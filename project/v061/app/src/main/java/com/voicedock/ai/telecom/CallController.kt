package com.voicedock.ai.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.voicedock.ai.notify.CallNotificationAction
import androidx.core.content.ContextCompat

/** Which route actually handled the call — reported to the user and written to the command log. */
enum class CallStrategy { TELECOM, NOTIFICATION_ACTION, DIALER_OPENED, DIGIT_KEYPAD, NONE }

data class CallOutcome(val ok: Boolean, val strategy: CallStrategy, val detail: String = "")

/**
 * v0.9.0 — the call layer, with an explicit fallback chain instead of wishful thinking.
 *
 * Placing a call is straightforward (`ACTION_CALL` + CALL_PHONE). Answering is not: Android's
 * `TelecomManager.acceptRingingCall()` exists since API 26 but is deprecated since 31 and is refused by
 * some OEM builds, so every path is tried in order and the one that worked is reported:
 *
 *   1. `accepted/ended via TelecomManager` (needs ANSWER_PHONE_CALLS)
 *   2. the ringing notification's own "Answer"/"Decline" action (this is also the only route that works
 *      for WhatsApp calls — WhatsApp exposes no call API at all)
 *   3. nothing → an honest "মানually pick up" message, never a fake success
 */
class CallController(private val ctx: Context) {

    private val tm: TelecomManager? get() = ctx.getSystemService(TelecomManager::class.java)

    fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun canPlace(): Boolean = hasPermission(Manifest.permission.CALL_PHONE)
    fun canAnswer(): Boolean = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)

    /** Place a call. Without CALL_PHONE this still does something useful: opens the dialer prefilled. */
    fun place(rawNumber: String): CallOutcome {
        val number = ContactMatch.sanitizeNumber(rawNumber)
            ?: return CallOutcome(false, CallStrategy.NONE, "invalid_number")
        val uri = Uri.fromParts("tel", number, null)
        if (!canPlace()) {
            val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.startActivity(dial)
                CallOutcome(true, CallStrategy.DIALER_OPENED, number)
            } catch (e: Exception) {
                CallOutcome(false, CallStrategy.NONE, e.javaClass.simpleName)
            }
        }
        return try {
            ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CallOutcome(true, CallStrategy.TELECOM, number)
        } catch (e: SecurityException) {
            // Permission vanished between check and call, or a device policy blocked it.
            Log.w(TAG, "ACTION_CALL refused: ${e.javaClass.simpleName}")
            CallOutcome(false, CallStrategy.NONE, "blocked")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CALL failed: ${e.javaClass.simpleName}")
            CallOutcome(false, CallStrategy.NONE, e.javaClass.simpleName)
        }
    }

    /** Answer whatever is ringing, by the best available route. */
    @SuppressLint("MissingPermission") // guarded immediately below, and SecurityException is caught
    fun answer(): CallOutcome {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val ok = runCatching { tm?.acceptRingingCall(); true }.getOrElse {
                Log.w(TAG, "acceptRingingCall failed: ${it.javaClass.simpleName}")
                false
            }
            if (ok) return CallOutcome(true, CallStrategy.TELECOM)
        }
        // VoIP (WhatsApp, Telegram…) and OEM dialers expose the answer button as a notification action.
        val fired = CallNotificationAction.fire(answer = true)
        if (fired != null) return CallOutcome(true, CallStrategy.NOTIFICATION_ACTION, fired)
        return CallOutcome(false, CallStrategy.NONE, "no_route")
    }

    @SuppressLint("MissingPermission") // guarded immediately below, and SecurityException is caught
    fun decline(): CallOutcome {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        // TelecomManager.endCall() is API 28+; below that the notification button is the only route.
        if (granted && Build.VERSION.SDK_INT >= 28) {
            val ok = runCatching { tm?.endCall(); true }.getOrElse {
                Log.w(TAG, "endCall failed: ${it.javaClass.simpleName}")
                false
            }
            if (ok) return CallOutcome(true, CallStrategy.TELECOM)
        }
        val fired = CallNotificationAction.fire(answer = false)
        if (fired != null) return CallOutcome(true, CallStrategy.NOTIFICATION_ACTION, fired)
        return CallOutcome(false, CallStrategy.NONE, "no_route")
    }

    /** Whether a default-dialer role request is even possible on this device. */
    fun supportsDialerRole(): Boolean = Build.VERSION.SDK_INT >= 29

    companion object {
        private const val TAG = "VoiceDockCall"
    }
}
