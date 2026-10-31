package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.command.CommandLog
import com.voicedock.ai.memory.MemoryStore
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.voicedock.ai.notify.NotificationAccess
import com.voicedock.ai.notify.NotifStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.0 — Settings → Commands.
 *
 * Honest by design: what the app understood, what it actually did, and the memories it holds are all
 * visible and deletable here. No hidden state, no cloud copy.
 */
@Composable
fun CommandsSection() {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val bn = s.uiLanguage == UiLanguage.BN
    var tick by remember { mutableIntStateOf(0) }

    val notifAccess = remember(tick) { NotificationAccess.isGranted(ctx) }
    val notifStore = remember(tick) { NotifStore(ctx.filesDir, VoiceDock.db(ctx).assistant()) }
    val captured = remember(tick) { runCatching { notifStore.all().size }.getOrDefault(0) }
    val memory = remember(tick) { MemoryStore(ctx.filesDir, VoiceDock.db(ctx).assistant()) }
    val log = remember(tick) { CommandLog(ctx.filesDir) }
    val memories = remember(tick) { runCatching { memory.all().take(8) }.getOrDefault(emptyList()) }
    val commands = remember(tick) { runCatching { log.recent(8) }.getOrDefault(emptyList()) }

    Text(tr("কমান্ড (Jarvis)", "Commands (Jarvis)"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        tr(
            "বাবলে কথা বলে ⏎ চেপে ধরলে সেটি কমান্ড হিসেবে চলে — যেমন “হোয়াটসঅ্যাপ খোলো”, “মনে রাখো কাল ৩টায় মিটিং”। কমান্ড না হলে সাধারণ লেখার মতোই বসে যায়।",
            "Dictate, then HOLD ⏎ to run it as a command — e.g. “open WhatsApp”, “remember meeting at 3”. If it is not a command, nothing changes: tap ⏎ inserts as usual.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("কমান্ড চালু", "Commands on"), Modifier.weight(1f))
        Switch(checked = s.commandsEnabled, onCheckedChange = { v -> SettingsStore.update { it.copy(commandsEnabled = v) } })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("উত্তর জোরে পড়ে শোনাও", "Speak results aloud"), Modifier.weight(1f))
        Switch(checked = s.speakResults, onCheckedChange = { v -> SettingsStore.update { it.copy(speakResults = v) } })
    }

    Text(
        tr(
            "চেনা যায় এমন কমান্ড: অ্যাপ খোলা · মনে রাখা/মনে করা · জোরে পড়া · AI · কল করা/ধরা/কাটা · মেসেজের সারমর্ম ও উত্তর · গান নিয়ন্ত্রণ · আনডু · ‘শেষ যে মেসেজ দিয়েছে তাকে ফোন করো’।",
            "Recognised commands: open an app · remember/recall · read aloud · ask the AI · place/answer/decline a call · message digest and reply · control the music · undo · “call the last person who messaged me”.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    // ---- v0.9.0 calls ----
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val callPerms = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
    )
    val missing = remember(tick) {
        callPerms.filter { ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("কল", "Calls"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            if (missing.isEmpty()) "কলের অনুমতি: ✓ সব দেওয়া আছে"
            else "কলের অনুমতি: ${missing.size} টি বাকি — ফোন করা/ধরা/কাটা কাজ করবে না।",
            if (missing.isEmpty()) "Call permissions: ✓ all granted"
            else "Call permissions: ${missing.size} missing — placing/answering/declining will not work.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { permLauncher.launch(missing.ifEmpty { callPerms }.toTypedArray()) }) {
            Text(tr("অনুমতি দিন", "Grant permissions"))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            TextButton(onClick = {
                runCatching {
                    val rm = ctx.getSystemService(RoleManager::class.java)
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    }
                }
            }) { Text(tr("ডিফল্ট ডায়ালার (ঐচ্ছিক)", "Default dialer (optional)")) }
        }
    }
    Text(
        tr(
            "কল ধরা: প্রথমে সিস্টেম API, না পারলে রিং হওয়া নোটিফিকেশনের ‘উত্তর’ বাটন (হোয়াটসঅ্যাপ কলেও এটি কাজ করে), তা না পারলে সৎভাবে ব্যর্থ হয় — ভুয়া ‘হয়ে গেছে’ বলে না।",
            "Answering order: system API → the ringing notification's own Answer button (this is also how WhatsApp calls work) → an honest failure. It never claims a success it did not have.",
        ),
        style = MaterialTheme.typography.labelSmall,
    )

    // ---- v0.10.0 media ----
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("মিউজিক", "Music"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            if (notifAccess) "নিয়ন্ত্রণ: ✓ সম্ভব — যেকোনো প্লেয়ার (Spotify, YouTube Music, স্টক প্লেয়ার) যা এখন বাজছে।"
            else "নিয়ন্ত্রণ: ✗ নোটিফিকেশন অ্যাক্সেস দরকার — সেটিংস → এই পেজেই ‘চালু করুন’ চাপুন।",
            if (notifAccess) "Control: ✓ ready — any player that is active (Spotify, YouTube Music, the stock player)."
            else "Control: ✗ needs notification access — use the button below.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        tr(
            "“পরের গান”, “গান থামাও”, “গান চালাও” — অ্যাপটি এখন যে প্লেয়ারটি বাজছে তাকেই কমান্ড পাঠায়; কোনো প্লেয়ার না থাকলে সৎভাবে বলে দেয়।",
            "“Next song”, “pause the music”, “play music” — the command goes to whichever player is active; with no player it says so instead of faking success.",
        ),
        style = MaterialTheme.typography.labelSmall,
    )

    // ---- v0.10.0 assistant role (long-press home) ----
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("ডিজিটাল অ্যাসিস্ট্যান্ট", "Digital assistant"), style = MaterialTheme.typography.titleSmall)
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = remember(tick) { ctx.getSystemService(RoleManager::class.java) }
        val holds = remember(tick) { runCatching { rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false) }
        Text(
            tr(
                if (holds) "সহকারী ভূমিকা: ✓ চালু — হোম বাটন লম্বা চেপে VoiceDock খুলবে।"
                else "সহকারী ভূমিকা: ✗ বন্ধ — চালু করলে হোম বাটন লম্বা চেপেই বাবল খুলবে।",
                if (holds) "Assistant role: ✓ on — long-press home opens VoiceDock."
                else "Assistant role: ✗ off — enable it to open the bubble with long-press home.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                runCatching {
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                        ctx.startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    } else tick++
                }
            }) { Text(tr(if (holds) "সেটিংস দেখুন" else "সহকারী হিসেবে সেট করুন", if (holds) "Open settings" else "Make it the assistant")) }
        }
        Text(
            tr(
                "এটি Android-এর ‘ডিফল্ট অ্যাপ → ডিজিটাল সহকারী’ সেটিংস খুলবে। অন্য সহকারী (Google Assistant) বদলে যাবে — আপনি যেকোনো সময় ফিরিয়ে দিতে পারবেন।",
                "This opens Android’s “Default apps → Digital assistant” screen. It replaces any other assistant (e.g. Google) — you can switch back at any time.",
            ),
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        Text(
            tr("এই Android সংস্করণে সহকারী ভূমিকা নেই (Android 10+ দরকার)।", "The assistant role needs Android 10 or newer."),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // ---- v0.8.0 notification access ----
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("মেসেজ (নোটিফিকেশন)", "Messages (notifications)"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            if (notifAccess) "নোটিফিকেশন অ্যাক্সেস: ✓ চালু — $captured টি নোটিফিকেশন সংরক্ষিত (সর্বোচ্চ ৪০০)।"
            else "নোটিফিকেশন অ্যাক্সেস: ✗ বন্ধ — “আজ কে মেসেজ দিয়েছে?” ও সরাসরি উত্তর কাজ করবে না।",
            if (notifAccess) "Notification access: ✓ on — $captured notifications stored (max 400)."
            else "Notification access: ✗ off — “who messaged me today?” and direct reply will not work.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(tr("নোটিফিকেশন থেকে পড়া", "Read from notifications"), Modifier.weight(1f))
        Switch(checked = s.assistantBackgroundEvents, onCheckedChange = { v -> SettingsStore.update { it.copy(assistantBackgroundEvents = v) } })
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { NotificationAccess.openSettings(ctx) }) { Text(tr("নোটিফিকেশন অ্যাক্সেস দিন", "Grant notification access")) }
        TextButton(onClick = { runCatching { notifStore.clear() }; tick++ }) { Text(tr("সংরক্ষিত সব মুছুন", "Clear stored")) }
    }
    Text(
        tr(
            "অ্যাপটি যা সংরক্ষণ করে: প্রেরকের নাম, সময়, ও মেসেজের লেখা (অ্যাপ প্রিভিউ বন্ধ রাখলে লেখা থাকে না)। কিছুই ইন্টারনেটে যায় না।",
            "What is stored: sender, time and message text (none if the app hides previews). Nothing is uploaded.",
        ),
        style = MaterialTheme.typography.labelSmall,
    )

    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("মনে রাখা কথা (${memories.size})", "Memories (${memories.size})"), style = MaterialTheme.typography.titleSmall)
    if (memories.isEmpty()) {
        Text(tr("কিছু নেই।", "Nothing saved yet."), style = MaterialTheme.typography.bodySmall)
    } else {
        memories.forEach { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                        Text(
                            SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(m.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { runCatching { memory.forget(m.id) }; tick++ }) {
                        Text(tr("মুছো", "Delete"))
                    }
                }
            }
        }
        TextButton(onClick = { runCatching { memory.clear() }; tick++ }) { Text(tr("সব মুছুন", "Clear all memories")) }
    }

    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(tr("শেষ কমান্ডগুলো", "Recent commands"), style = MaterialTheme.typography.titleSmall)
    if (commands.isEmpty()) {
        Text(tr("এখনো কোনো কমান্ড নেই।", "No commands yet."), style = MaterialTheme.typography.bodySmall)
    } else {
        commands.forEach { c ->
            val mark = if (c.ok) "✓" else "✗"
            Text(
                "$mark ${c.raw.take(40)} → ${c.intent} (${c.source}/${c.strategy})",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        TextButton(onClick = { runCatching { log.clear() }; tick++ }) { Text(tr("লগ মুছুন", "Clear log")) }
    }
    if (!bn) Unit
}
