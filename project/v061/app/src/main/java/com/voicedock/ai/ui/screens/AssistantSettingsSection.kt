package com.voicedock.ai.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.context.ProbePolicy
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.security.ProbeAudit
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import java.text.DateFormat
import java.util.Date

/**
 * Just-in-time, granular assistant controls. Every sensitive switch is OFF by default and can be
 * turned off at any time. This is the UI for the v0.6 `AppSettings.assistant*` fields.
 *
 * v0.6.4 adds the two privacy items that were still open after the v0.6 handoff:
 * a user-maintained **probe blocklist** (checked before any node is traversed) and a local
 * **audit counter** of how often the screen was read or refused.
 */
@Composable
fun AssistantSettingsSection(resumeTick: Int) {
    val s by SettingsStore.state.collectAsState()
    val ctx = LocalContext.current
    val a11y = remember(resumeTick) { Permissions.accessibility(ctx) }
    val notif = remember(resumeTick) { Permissions.notificationListener(ctx) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("ভাসমান সহকারী (v0.6)", "Floating assistant (v0.6)"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("বাবলে চাপ দিলেই প্রসঙ্গ বুঝে সাহায্য করে। স্ক্রিন শুধু আপনার ট্যাপে, শুধু দৃশ্যমান লেখা — ব্যাকগ্রাউন্ডে নয়।",
                    "Tap the bubble for contextual help. The screen is read only on your tap, visible text only — never in the background."),
                style = MaterialTheme.typography.bodySmall,
            )
            AssistantToggle(tr("চাপ দিলে স্বয়ংক্রিয় স্ক্রিন স্ক্যান", "Auto screen scan on tap"), s.assistantAutoScan) { v -> SettingsStore.update { it.copy(assistantAutoScan = v) } }
            AssistantToggle(tr("স্ক্রিন প্রসঙ্গ পড়ার অনুমতি", "Screen-context consent"), s.assistantScreenContext) { v -> SettingsStore.update { it.copy(assistantScreenContext = v) } }
            if (!s.assistantScreenContext) Text(
                tr("বন্ধ থাকলে সহকারী শুধু নিজের বাবলে কথা বলবে, কোনো স্ক্রিন তথ্য নেবে না।",
                    "While off, the assistant stays in its own bubble and takes no screen data."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            )
            AssistantToggle(tr("catch-up সারসংক্ষেপ", "Catch-up summary"), s.assistantCatchUp) { v -> SettingsStore.update { it.copy(assistantCatchUp = v) } }
            AssistantToggle(tr("নিজে টাইপ করে greeting দেখাও", "Self-typing greeting"), s.assistantSelfTyping) { v -> SettingsStore.update { it.copy(assistantSelfTyping = v) } }
            AssistantToggle(tr("পেছনের স্ক্রিন dim / blur", "Dim / blur background"), s.assistantDimBlur) { v -> SettingsStore.update { it.copy(assistantDimBlur = v) } }
            AssistantToggle(tr("‘স্ক্রিন বুঝাও’-তে স্ক্রিনশট", "Screenshot for ‘Explain screen’"), s.assistantScreenshot) { v -> SettingsStore.update { it.copy(assistantScreenshot = v) } }

            ProbeBlocklistEditor()

            Text(tr("অনুমতি অবস্থা", "Permission status"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a11y) "✓" else "•", color = if (a11y) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(tr(" Accessibility — স্ক্রিন প্রসঙ্গের জন্য", " Accessibility — for screen context"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(Permissions.accessibilitySettings().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(tr("খুলুন", "Open"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (notif) "✓" else "•", color = if (notif) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(tr(" Notification access — v0.7 ইভেন্ট", " Notification access — v0.7 events"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(Permissions.notificationListenerSettings().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(tr("খুলুন", "Open"))
                }
            }
            Text(
                tr("ব্যাকগ্রাউন্ড ইভেন্ট সংগ্রহ, কন্টাক্ট ম্যাপিং ও স্থায়ী মেমোরি v0.7–v0.8-এ যোগ হবে; এখন কিছুই সংগ্রহ করা হয় না।",
                    "Background event capture, contact mapping and persistent memory arrive in v0.7–v0.8; nothing is collected now."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * User-maintained probe blocklist. Entries are matched case-insensitively and also cover child
 * packages (`com.example.bank` blocks `com.example.bank.wallet`). The list is checked BEFORE the
 * accessibility tree is walked, so a blocked app's window is never read at all.
 */
@Composable
private fun ProbeBlocklistEditor() {
    val s by SettingsStore.state.collectAsState()
    val ctx = LocalContext.current
    val blocked = remember(s.probeBlockedPackages) { s.blockedPackages() }
    var draft by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    val valid = ProbePolicy.normalize(draft) != null

    Text(
        tr("স্ক্রিন পড়া থেকে বাদ দেওয়া অ্যাপ", "Apps blocked from screen reading"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        tr("এখানে থাকা অ্যাপের স্ক্রিন VoiceDock একবারও পড়ে না — একটি নোডও দেখা হয় না। OTP/সুরক্ষিত স্ক্রিন শনাক্তকরণ সবসময় নির্ভুল নয়, তাই সংবেদনশীল অ্যাপ এখানে রাখাই নিরাপদ।",
            "VoiceDock never reads the screen of an app listed here — not even one node is inspected. Sensitive-screen detection is heuristic, so keeping sensitive apps here is the safest option."),
        style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(tr("প্যাকেজ নাম (যেমন com.whatsapp)", "Package name (e.g. com.whatsapp)")) },
            singleLine = true,
            isError = draft.isNotBlank() && !valid,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            enabled = valid && blocked.size < ProbePolicy.MAX_PACKAGES,
            onClick = {
                SettingsStore.update { it.copy(probeBlockedPackages = ProbePolicy.add(it.probeBlockedPackages, draft)) }
                draft = ""
            },
        ) { Text(tr("যোগ", "Add")) }
    }
    if (draft.isNotBlank() && !valid) Text(
        tr("প্যাকেজ নাম হতে হবে ছোট হাতের অক্ষর, অন্তত একটি ডট সহ (যেমন org.example.app)।",
            "A package name has to be lowercase with at least one dot (e.g. org.example.app)."),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
    )
    TextButton(onClick = { showPicker = !showPicker }) {
        Text(if (showPicker) tr("ইনস্টল করা অ্যাপের তালিকা বন্ধ করুন ▲", "Hide installed apps ▲")
        else tr("ইনস্টল করা অ্যাপ থেকে বেছে নিন ▼", "Pick from installed apps ▼"))
    }
    if (showPicker) InstalledAppPicker(
        already = blocked,
        onPick = { pkg ->
            SettingsStore.update { it.copy(probeBlockedPackages = ProbePolicy.add(it.probeBlockedPackages, pkg)) }
        },
    )
    if (blocked.isEmpty()) {
        Text(
            tr("তালিকা খালি — শুধু অ্যাপের নিজস্ব নিয়ম (ব্যাংক/বিকাশ/নগদ ইত্যাদি) কাজ করছে।",
                "List is empty — only the built-in rules (banks, bKash, Nagad, …) are active."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
    } else {
        blocked.forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("🚫 $pkg", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    SettingsStore.update { it.copy(probeBlockedPackages = ProbePolicy.remove(it.probeBlockedPackages, pkg)) }
                }) { Text(tr("বাদ", "Remove")) }
            }
        }
        TextButton(onClick = { SettingsStore.update { it.copy(probeBlockedPackages = emptyList()) } }) {
            Text(tr("সব বাদ দিন", "Clear all"))
        }
    }
    ProbeAuditRows()
}

/** Launcher-visible apps only (no QUERY_ALL_PACKAGES needed: the manifest already declares launcher <queries>). */
@Composable
private fun InstalledAppPicker(already: List<String>, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        runCatching {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == ctx.packageName) return@mapNotNull null
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                    label to pkg
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        }.getOrDefault(emptyList())
    }
    if (apps.isEmpty()) {
        Text(tr("কোনো অ্যাপ পাওয়া যায়নি।", "No apps found."), style = MaterialTheme.typography.labelSmall)
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        apps.forEach { (label, pkg) ->
            val added = already.any { it.equals(pkg, ignoreCase = true) }
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !added) { onPick(pkg) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (added) "✓" else "＋", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(" $label", style = MaterialTheme.typography.bodySmall)
                    Text(" $pkg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Local audit counter: how often the screen was read, and how often it was refused. Counts only. */
@Composable
private fun ProbeAuditRows() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val audit = remember(refresh) { ProbeAudit.read(ctx) }
    val stamp = remember(audit.lastAt) {
        if (audit.lastAt <= 0L) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(audit.lastAt))
    }
    Text(
        tr("স্ক্রিন পড়ার হিসাব (শুধু এই ফোনে)", "Screen-read audit (this phone only)"),
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        tr("মোট চেষ্টা: ${audit.probes} · আটকানো: ${audit.refused} · ব্যবহৃত: ${audit.allowed}\nশেষ চেষ্টা: $stamp",
            "Attempts: ${audit.probes} · refused: ${audit.refused} · used: ${audit.allowed}\nLast attempt: $stamp"),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        tr("শুধু সংখ্যা রাখা হয় — কোন অ্যাপ বা কী লেখা ছিল তা নয়। কিছু আপলোড হয় না।",
            "Only counters are kept — never which app or what text. Nothing is uploaded."),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { refresh++ }) { Text(tr("রিফ্রেশ", "Refresh")) }
        TextButton(onClick = { ProbeAudit.reset(ctx); refresh++ }) { Text(tr("হিসাব মুছুন", "Reset counters")) }
    }
}

@Composable
private fun AssistantToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
