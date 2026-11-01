package com.voicedock.ai.device

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.voicedock.ai.telecom.ContactEntry

/**
 * v1.3.0 — the one place that reads the phone's real topology: SIMs, my numbers, call accounts, every
 * WhatsApp instance (including clones in other user profiles), and Termux.
 *
 * Every accessor is permission-guarded and returns an empty list plus a reason instead of throwing, because a
 * voice assistant that crashes on a missing permission is worse than one that says "permission needed".
 */
object DeviceAccess {

    // ---- SIMs ---------------------------------------------------------------------------------

    fun hasPhoneState(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasNumbers(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun sims(ctx: Context): List<SimInfo> = runCatching {
        if (!hasPhoneState(ctx)) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: return emptyList()
        val defaultVoice: Int = runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() }.getOrDefault(-1)
        val defaultData = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        list.filter { it.simSlotIndex >= 0 }.map { si ->
            SimInfo(
                slot = si.simSlotIndex,
                displaySlot = si.simSlotIndex + 1,
                subscriptionId = si.subscriptionId,
                carrier = si.carrierName?.toString().orEmpty(),
                label = runCatching { si.displayName?.toString().orEmpty() }.getOrDefault(""),
                number = if (hasNumbers(ctx)) runCatching { si.number }.getOrNull()?.takeIf { it.isNotBlank() } else null,
                isDefaultVoice = si.subscriptionId == defaultVoice,
                isDefaultData = si.subscriptionId == defaultData,
                active = true,
            )
        }.sortedBy { it.slot }
    }.getOrDefault(emptyList())

    fun myNumbers(ctx: Context, bengali: Boolean): String = SimCore.myNumbers(sims(ctx), bengali)

    // ---- call accounts (which SIM a call goes out on) ------------------------------------------

    @Suppress("MissingPermission")
    fun callAccounts(ctx: Context): List<Pair<PhoneAccountHandle, String>> = runCatching {
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return emptyList()
        tm.callCapablePhoneAccounts.mapNotNull { handle ->
            runCatching { tm.getPhoneAccount(handle)?.label?.toString()?.let { handle to it } }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /**
     * The account that corresponds to [sim]. Android does not expose the mapping directly, so it is inferred by
     * the manager's own naming: the label usually contains the SIM slot ("SIM 1"), the carrier name or the slot
     * index of the account list. When it cannot be inferred, null is returned and the call falls back to the
     * system default **with a message saying so** — never a silent wrong SIM.
     */
    fun accountFor(sim: SimInfo?, accounts: List<Pair<PhoneAccountHandle, String>>): PhoneAccountHandle? {
        if (sim == null || accounts.isEmpty()) return null
        if (accounts.size == 1) return null // nothing to choose between
        val carrier = sim.carrier.lowercase()
        accounts.firstOrNull { (_, label) ->
            val l = label.lowercase()
            l.contains("sim ${sim.displaySlot}") || l.contains("সিম ${SimCore.bn(sim.displaySlot)}") ||
                (carrier.isNotBlank() && l.contains(carrier))
        }?.let { return it.first }
        // Fall back to position, which matches the physical slot on stock Android.
        return accounts.getOrNull(sim.slot)?.first
    }

    /**
     * Place a call through a specific SIM. Returns null on success, or the reason it could not be done
     * (the caller then uses the normal dialer path and says which SIM was used).
     */
    @Suppress("MissingPermission")
    fun placeCallOnSim(ctx: Context, number: String, sim: SimInfo?): String? = runCatching {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "no_call_permission"
        }
        if (Build.VERSION.SDK_INT < 26) return "old_android"
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return "no_telecom"
        val handle = accountFor(sim, callAccounts(ctx)) ?: return "no_account"
        val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
        tm.placeCall(Uri.fromParts("tel", number, null), extras)
        null
    }.getOrElse { e -> e.javaClass.simpleName }

    // ---- chat apps, including clones in other profiles ----------------------------------------

    fun chatInstances(ctx: Context): List<ChatInstance> = runCatching {
        val la: LauncherApps = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val um: UserManager = ctx.getSystemService(UserManager::class.java) ?: return emptyList()
        val profiles: List<UserHandle> = runCatching { la.profiles }.getOrDefault(listOf(android.os.Process.myUserHandle()))
        val out = ArrayList<ChatInstance>()
        for (pkg in ChatKind.chatPackages) {
            for (profile in profiles) {
                val activities = runCatching { la.getActivityList(pkg, profile) }.getOrDefault(emptyList())
                val visible = activities.isNotEmpty()
                val installed = runCatching { la.getApplicationInfo(pkg, 0, profile) }.isSuccess
                if (!visible && !installed) continue
                val label = activities.firstOrNull()?.label?.toString()
                    ?: ChatKind.of(pkg).labelEn
                val serial = runCatching { um.getSerialNumberForUser(profile) }.getOrDefault(0L)
                out += ChatInstance(
                    kind = ChatKind.of(pkg),
                    packageName = pkg,
                    userId = serial.toInt(),
                    label = label,
                    cloned = serial != 0L,
                    installed = true,
                )
            }
        }
        ChatInstanceCore.dedupe(out)
    }.getOrDefault(emptyList())

    fun whatsapps(ctx: Context): List<ChatInstance> = ChatInstanceCore.whatsapps(chatInstances(ctx))

    /** Launch one exact instance (the clone included) — the only way to open a second WhatsApp reliably. */
    fun launchInstance(ctx: Context, instance: ChatInstance): Boolean = runCatching {
        val la: LauncherApps = ctx.getSystemService(LauncherApps::class.java) ?: return false
        val um: UserManager = ctx.getSystemService(UserManager::class.java) ?: return false
        val profile = um.getUserForSerialNumber(instance.userId.toLong()) ?: return false
        val activity = la.getActivityList(instance.packageName, profile).firstOrNull() ?: return false
        la.startMainActivity(activity.componentName, profile, android.graphics.Rect(), null)
        true
    }.getOrDefault(false)

    // ---- Termux -------------------------------------------------------------------------------

    fun termuxInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    /**
     * Sends one shell command to Termux through its documented `RUN_COMMAND` service.
     *
     * Needs **both** sides to agree: Termux's `allow-external-apps=true` in `~/.termux/termux.properties`, and
     * this app declaring `com.termux.permission.RUN_COMMAND`. Neither can be checked from here, so the call
     * reports what it actually did instead of claiming the command ran.
     */
    fun runTermux(ctx: Context, command: String, background: Boolean = true, resultIntent: PendingIntent? = null): String? = runCatching {
        if (!termuxInstalled(ctx)) return "not_installed"
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SERVICE", ComponentName("com.termux", "com.termux.app.TermuxService"))
            if (resultIntent != null) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
        }
        ctx.startService(intent)
        null // dispatched; the result (if any) arrives through the PendingIntent
    }.getOrElse { e -> e.javaClass.simpleName }

    /** The numbers the phone knows, for identity resolution. Empty without READ_CONTACTS. */
    fun contacts(ctx: Context): List<ContactEntry> = runCatching {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            emptyList()
        } else com.voicedock.ai.telecom.ContactResolver(ctx).all()
    }.getOrDefault(emptyList())
}
