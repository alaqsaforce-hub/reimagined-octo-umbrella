package com.voicedock.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.command.CommandLog
import com.voicedock.ai.command.CommandLogEntry
import com.voicedock.ai.draft.DraftCore
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.insertion.TextInserter
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.delay
import java.io.File

/**
 * v1.3.1 — the voice-to-voice surface, in the shape people already know from ChatGPT: one column of turns,
 * a live transcript while speaking, an orb you tap to talk, and the answer/result of every utterance.
 *
 * It is not a mock-up and not a second assistant: the conversation is built from two things that already
 * exist and are already tested — the dictation engine ([DictationController]) and the command log
 * (`CommandLog`, which records what was said, what it was understood as and whether it worked). That is why
 * the assistant's turn is the real outcome ("কল করা হচ্ছে ✓", "কল করা যায়নি (অনুমতি বন্ধ)"), not a canned
 * reply.
 *
 * Honest labels: the transcript is on the phone only; nothing is sent anywhere unless a command explicitly
 * asks for it, and no turn is inserted into another app without a tap on "ঢোকাও".
 */
@Composable
fun VoiceScreen(
    controller: DictationController,
    openSettings: () -> Unit = {},
    runCommand: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    val ui by controller.ui.collectAsState()
    val live = remember(ui) { DraftCore.tidy(ui.live.ifBlank { ui.draft }) }

    // The assistant's turns come from the real command log; poll it slowly (it is a small local file).
    var entries by remember { mutableStateOf<List<CommandLogEntry>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        entries = runCatching { CommandLog(File(ctx.filesDir, "commands")).recent(20) }.getOrDefault(emptyList())
        delay(1_800)
        tick++
    }

    var lastResult by remember { mutableStateOf("") }
    val listening = ui.micOn ||
        ui.phase == DictationPhase.LISTENING || ui.phase == DictationPhase.PROCESSING
    val hasAccessibility = remember(tick) { InsertAccessibilityService.instance != null }
    val plan = remember(live, hasAccessibility) { DraftCore.plan(live, hasAccessibility) }

    Column(Modifier.fillMaxSize()) {
        // ---- turns ----
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            reverseLayout = false,
        ) {
            item {
                Text(DraftCore.voiceHint(bn), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (entries.isEmpty() && live.isBlank()) {
                item {
                    Text(DraftCore.voiceEmpty(bn), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(entries.reversed()) { e -> TurnCard(e, bn) }
            if (live.isNotBlank()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { LiveTurn(live, listening, bn) }
            }
        }

        // ---- the draft card: shown, never auto-inserted ----
        if (DraftCore.presentable(live)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(DraftCore.title(bn), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(live, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (bn) plan.noteBn else plan.noteEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (lastResult.isNotBlank()) {
                        Text(lastResult, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            // Same honest insert path as the bubble: accessibility if present, else clipboard.
                            val out = TextInserter.insert(ctx, plan.text)
                            lastResult = if (bn) out.result.bn else out.result.en
                        }) { Text(DraftCore.insertLabel(bn)) }
                        TextButton(onClick = { controller.clear(); lastResult = "" }) {
                            Text(DraftCore.discardLabel(bn))
                        }
                        TextButton(onClick = { runCommand(plan.text) }) { Text(tr("⚡ কমান্ড", "⚡ Command")) }
                    }
                }
            }
        }

        HorizontalDivider()

        // ---- controls ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { if (ui.micOn) controller.pause() else controller.start() }) {
                Text(if (ui.micOn) tr("⏸ থামাও", "⏸ Pause") else tr("🎤 বলো", "🎤 Speak"))
            }
            TextButton(onClick = { controller.startNew() }) { Text(tr("নতুন", "New")) }
            TextButton(onClick = { controller.clear() }) { Text(tr("🗑 পরিষ্কার", "🗑 Clear")) }
            Spacer(Modifier.weight(1f))
            // The orb: tap = listen, tap again = pause. Big enough to hit without looking.
            Surface(
                shape = CircleShape,
                color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { if (ui.micOn) controller.pause() else controller.start() },
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    val bar = (ui.level.coerceIn(0f, 1f) * 34f + 12f).dp
                    Box(
                        Modifier.width(bar).height(6.dp)
                            .background(if (listening) Color.White else MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
        val note = ui.note.orEmpty()
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openSettings) { Text(tr("⚙ সেটিংস", "⚙ Settings")) }
        }
    }
}

/** One finished exchange: what was said, and what actually happened. */
@Composable
private fun TurnCard(e: CommandLogEntry, bn: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Text(e.raw, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(
                    e.detail.ifBlank {
                        if (e.ok) tr("সম্পন্ন ✓", "Done ✓") else tr("হয়নি", "Not done")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    (if (e.ok) "✓ " else "✗ ") + e.intent + " · " + e.source +
                        if (bn) " · ${e.confidence.toInt()}%" else " · ${e.confidence.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun LiveTurn(live: String, listening: Boolean, bn: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(0.95f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(live, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (listening) tr("শুনছি…", "Listening…") else tr("থামানো", "Paused"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

