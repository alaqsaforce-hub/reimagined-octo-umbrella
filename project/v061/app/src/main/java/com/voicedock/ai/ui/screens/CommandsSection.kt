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
import com.voicedock.ai.command.CommandLog
import com.voicedock.ai.memory.MemoryStore
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

    val memory = remember(tick) { MemoryStore(ctx.filesDir) }
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
            "এখনো চালু হয়নি (পরের ভার্সনে): ফোন করা/ধরা/কাটা (v0.9), মেসেজ পড়া ও উত্তর (v0.8), গান নিয়ন্ত্রণ (v0.10)। এই কমান্ডগুলো চিনতে পারে এবং কী করতে হবে জিজ্ঞেস করে — ভুয়া ‘হয়ে গেছে’ বলে না।",
            "Not enabled yet (later versions): calls (v0.9), messages (v0.8), media (v0.10). Those are recognised and explained instead of being faked.",
        ),
        style = MaterialTheme.typography.bodySmall,
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
