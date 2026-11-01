package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.agents.TaskBoard
import com.voicedock.ai.agents.TaskBoardStore
import com.voicedock.ai.agents.TaskRunner
import com.voicedock.ai.agents.TaskStatus
import com.voicedock.ai.agents.TerminalPolicy
import com.voicedock.ai.device.ChatInstanceCore
import com.voicedock.ai.device.DeviceAccess
import com.voicedock.ai.device.SimCore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr

/**
 * v1.3.0 — "ডিভাইস অ্যাক্সেস" and "কাজের বোর্ড".
 *
 * Every line here is a *probe*, never a stored flag: the SIMs come from `SubscriptionManager`, the numbers
 * from the SIM records (masked — a screenshot must not leak a full number), the chat instances from
 * `LauncherApps` (the only API that can see a second profile such as Dual Messenger), and Termux from the
 * package manager. Where the phone cannot answer (which internal call account belongs to SIM 2) the screen
 * says so instead of inventing a mapping.
 */
@androidx.compose.foundation.layout.ExperimentalLayoutApi
@Composable
fun DeviceAccessSection(resumeTick: Int) {
    val ctx = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    var tick by remember { mutableIntStateOf(0) }
    var termuxTest by remember { mutableStateOf("") }

    val sims = remember(resumeTick, tick) { runCatching { DeviceAccess.sims(ctx) }.getOrDefault(emptyList()) }
    val chats = remember(resumeTick, tick) { runCatching { DeviceAccess.chatInstances(ctx) }.getOrDefault(emptyList()) }
    val termuxPresent = remember(resumeTick, tick) { runCatching { DeviceAccess.termuxInstalled(ctx) }.getOrDefault(false) }

    Text(tr("ডিভাইস অ্যাক্সেস", "Device access"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

    // ---- SIMs ------------------------------------------------------------------------------------
    Text(tr("সিম", "SIMs"), style = MaterialTheme.typography.titleSmall)
    if (sims.isEmpty()) {
        Text(
            if (!DeviceAccess.hasPhoneState(ctx)) {
                tr(
                    "সিম দেখা যাচ্ছে না — ‘কলের অবস্থা’ অনুমতি নেই। উপরের তালিকায় দিন।",
                    "SIMs are invisible — the ‘phone state’ permission is missing. Grant it in the list above.",
                )
            } else {
                tr("এই ফোনে সচল সিম পাওয়া যায়নি।", "No active SIM was found on this phone.")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    sims.forEach { s ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.title + if (s.isDefaultVoice) tr(" · ডিফল্ট কল", " · default calls") else "", style = MaterialTheme.typography.labelLarge)
                Text(
                    tr(
                        "নিজের নম্বর: ${s.number?.let { SimCore.mask(it) } ?: "পড়া যাচ্ছে না (অনুমতি নেই বা সিমে সংরক্ষিত নেই)"}",
                        "Own number: ${s.number?.let { SimCore.mask(it) } ?: "not readable (no permission, or not stored on the SIM)"}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Text(
        tr(
            "“সিম ২ দিয়ে রহিমকে ফোন করো” বললে ঠিক ওই সিমে কল দেওয়ার চেষ্টা হবে। ফোন কোন সিম ব্যবহার করল তা নিশ্চিত না হলে অ্যাপ সেটা বলবে।",
            "“Call Rahim on SIM 2” aims the call at that SIM. If the phone cannot be pinned to it, the app says so.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    HorizontalDivider(Modifier.padding(vertical = 6.dp))

    // ---- chat instances --------------------------------------------------------------------------
    Text(tr("চ্যাট অ্যাপ ও ক্লোন", "Chat apps and clones"), style = MaterialTheme.typography.titleSmall)
    if (chats.isEmpty()) {
        Text(
            tr("হোয়াটসঅ্যাপ/মেসেঞ্জার পাওয়া যায়নি।", "No WhatsApp/Messenger installation was found."),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        chats.forEach { c ->
            Text("• " + ChatInstanceCore.describe(c, bn), style = MaterialTheme.typography.bodySmall)
        }
        val whats = ChatInstanceCore.whatsapps(chats)
        if (whats.size > 1) {
            Text(
                tr(
                    "একাধিক হোয়াটসঅ্যাপ আছে (${whats.size}) — কোনটা দিয়ে পাঠাবে অ্যাপ জিজ্ঞেস করবে, নিজে ধরে নেবে না। " +
                        "ক্লোন করা ইনস্ট্যান্সে সরাসরি উত্তর দেওয়ার প্রতিশ্রুতি নেই।",
                    "More than one WhatsApp (${whats.size}) — the app asks which one and never guesses. " +
                        "A cloned instance gets no direct-reply promise.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 6.dp))

    // ---- Termux ----------------------------------------------------------------------------------
    Text(tr("টার্মাক্স (প্রোট/ওপেনকোড)", "Termux (proot / OpenCode)"), style = MaterialTheme.typography.titleSmall)
    Text(
        if (termuxPresent) tr("✓ টার্মাক্স ইনস্টল আছে", "✓ Termux is installed")
        else tr("✗ টার্মাক্স ইনস্টল নেই — Play Store/F-Droid থেকে ইনস্টল করুন", "✗ Termux is not installed — install it from Play Store/F-Droid"),
        style = MaterialTheme.typography.bodySmall,
        color = if (termuxPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    DeviceToggle(
        tr("টার্মাক্সে কমান্ড চালানো (ডিফল্ট বন্ধ)", "Run commands in Termux (off by default)"),
        settings.termuxEnabled,
    ) { v -> SettingsStore.update { it.copy(termuxEnabled = v) } }
    Text(
        tr(
            "কেবল পড়ার কমান্ড (ls, pwd, termux-info…) অনুমতি ছাড়া চলে; ফোন বদলাতে পারে এমন কমান্ড প্রতিবার নিশ্চিত করতে হয়; " +
                "rm -rf / বা mkfs জাতীয় কমান্ড কখনোই চলে না। টার্মাক্সে ~/.termux/termux.properties ফাইলে " +
                "allow-external-apps=true থাকতে হবে, নাহলে কমান্ড পৌঁছাবে না — অ্যাপ সেটাই বলবে।",
            "Read-only commands (ls, pwd, termux-info…) run without a confirmation; anything that can change the " +
                "phone is confirmed every time; rm -rf / or mkfs never run. Termux must have " +
                "allow-external-apps=true in ~/.termux/termux.properties or the command does not arrive — and " +
                "the app says exactly that.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Row {
        TextButton(onClick = {
            val reason = runCatching { DeviceAccess.runTermux(ctx, "termux-info") }.getOrNull()
            termuxTest = if (reason == null) {
                TerminalPolicy.dispatchedText("termux-info", termuxPresent, bn)
            } else {
                tr("পাঠানো যায়নি ($reason) — allow-external-apps চালু আছে কি?", "Not dispatched ($reason) — is allow-external-apps on?")
            }
        }) { Text(tr("পরীক্ষা: termux-info", "Test: termux-info")) }
    }
    if (termuxTest.isNotBlank()) Text(termuxTest, style = MaterialTheme.typography.labelSmall)

    HorizontalDivider(Modifier.padding(vertical = 6.dp))

    // ---- task board ------------------------------------------------------------------------------
    TaskBoardSection(resumeTick, bn, tick) { tick++ }
}

@androidx.compose.foundation.layout.ExperimentalLayoutApi
@Composable
private fun TaskBoardSection(resumeTick: Int, bn: Boolean, tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<com.voicedock.ai.agents.AgentTask>>(emptyList()) }
    LaunchedEffect(resumeTick, tick) {
        tasks = runCatching { TaskBoardStore(ctx).load() }.getOrDefault(emptyList()).reversed()
    }

    Text(tr("কাজের বোর্ড", "Task board"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr(
            "কাজ লিখুন — ‘;’ বা ‘তারপর’ দিয়ে ধাপ আলাদা করুন। এজেন্ট ব্যাকগ্রাউন্ডে একের পর এক চালাবে; " +
                "প্রতিটি ধাপ সাধারণ কমান্ডের মতোই অনুমতি ও সীমা মেনে চলে।",
            "Write a task — separate steps with ‘;’ or ‘then’. The agent runs them one after another in the " +
                "background; every step obeys the same permissions and limits as a spoken command.",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(tr("কাজ ও নির্দেশনা", "Task and instructions")) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    )
    Row {
        TextButton(onClick = {
            val steps = TaskBoard.parseSteps(draft)
            if (steps.isNotEmpty()) {
                TaskBoardStore(ctx).add(steps.first().take(40), steps, System.currentTimeMillis(), "t" + System.currentTimeMillis())
                draft = ""
                onChanged()
            }
        }) { Text(tr("যোগ করো", "Add")) }
        TextButton(onClick = { TaskRunner.start(ctx) }) { Text(tr("চালাও", "Run queued")) }
        TextButton(onClick = { TaskRunner.cancel(ctx, null); onChanged() }) { Text(tr("চলমান বাতিল", "Cancel running")) }
    }
    if (tasks.isEmpty()) {
        Text(
            tr("বোর্ড খালি।", "The board is empty."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    tasks.take(8).forEach { t ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.label(bn), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                    t.steps.forEachIndexed { i, s ->
                        val mark = when (s.status) {
                            TaskStatus.DONE -> "✓"
                            TaskStatus.FAILED -> "✗"
                            TaskStatus.CANCELLED -> "—"
                            else -> "•"
                        }
                        Text("$mark ${SimCore.bn(i + 1)}. ${s.command}" + if (s.note.isNotBlank()) " (${s.note.take(60)})" else "",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.status == TaskStatus.QUEUED || t.status == TaskStatus.PAUSED) {
                        TextButton(onClick = { TaskRunner.start(ctx, t.id); onChanged() }) { Text(tr("চালাও", "Run")) }
                    }
                    if (t.status != TaskStatus.DONE && t.status != TaskStatus.CANCELLED) {
                        TextButton(onClick = { TaskRunner.cancel(ctx, t.id); onChanged() }) { Text(tr("বাতিল", "Cancel")) }
                    }
                }
            }
        }
    }
}

@androidx.compose.foundation.layout.ExperimentalLayoutApi
@Composable
private fun DeviceToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Small helper so the Settings screen stays a list of sections. */
@androidx.compose.foundation.layout.ExperimentalLayoutApi
@Composable
fun DeviceAndTasksSection(resumeTick: Int) {
    DeviceAccessSection(resumeTick)
}
