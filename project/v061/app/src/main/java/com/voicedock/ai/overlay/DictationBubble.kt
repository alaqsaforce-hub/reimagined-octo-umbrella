package com.voicedock.ai.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.VoiceDockTheme
import com.voicedock.ai.voice.DictationPhase

/**
 * v0.6.6 — the small, self-growing dictation bubble.
 *
 * It replaces the tall "context assistant" panel as the FIRST thing a tap opens. Rules:
 *  - it is only as big as the text inside it (a few words = tiny bubble, a paragraph = taller),
 *  - it never covers more than ~a third of the screen; longer text scrolls inside,
 *  - its window is NOT focusable, so the app underneath (WhatsApp's message box) keeps the cursor,
 *  - three small icons at the bottom right: ⏎ insert · ✨ tidy up · 💬 ask the AI,
 *  - nothing is inserted anywhere without pressing ⏎.
 */
@Composable
fun DictationBubble(
    state: DictationBubbleState,
    onInsert: () -> Unit,
    onRephrase: () -> Unit,
    onAsk: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /** v0.7.0 — the ⏎ chip held down means "treat what I said as a command". */
    onCommand: () -> Unit = {},
    /** v0.7.0 — a clarification / confirmation button was tapped. */
    onChoice: (Int) -> Unit = {},
    /** v0.10.0 — take back the action that just ran (only offered when it is real). */
    onUndo: () -> Unit = {},
) {
    val cfg = LocalConfiguration.current
    val maxWidth = (cfg.screenWidthDp * 0.68f).dp
    val maxTextHeight = (cfg.screenHeightDp * 0.20f).dp
    val maxAnswerHeight = (cfg.screenHeightDp * 0.22f).dp
    val hasText = state.liveText.isNotBlank()

    VoiceDockTheme {
        Card(
            modifier = Modifier.widthIn(min = 170.dp, max = maxWidth),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        ) {
            Column(Modifier.padding(start = 11.dp, end = 9.dp, top = 7.dp, bottom = 7.dp)) {
                // ---- status row (tiny) ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(state.dotColor(), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        state.statusLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) {
                        Text("✕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }

                // ---- the live text: this is what makes the bubble grow ----
                Box(Modifier.heightIn(max = maxTextHeight).verticalScroll(rememberScrollState())) {
                    Text(
                        if (hasText) state.liveText else state.placeholder(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasText) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                }

                if (state.busy) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.busyLabel.ifBlank { tr("একটু অপেক্ষা করুন…", "Working…") },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // ---- the AI answer (only after 💬) ----
                if (state.answer.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Box(Modifier.heightIn(max = maxAnswerHeight).verticalScroll(rememberScrollState())) {
                        Text(state.answer, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.providerLabel.isNotBlank()) {
                        Text(
                            state.providerLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                if (state.note.isNotBlank()) {
                    Text(
                        state.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ---- undo (v0.10.0): shown only for actions that can genuinely be reversed ----
                if (state.undoLabel.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onUndo,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        Text(
                            state.undoLabel,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // ---- clarification / confirmation buttons (v0.7.0) ----
                if (state.choices.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        state.choices.take(4).forEachIndexed { i, label ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { onChoice(i) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ---- three small actions, bottom right ----
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.needsSetup) {
                        MiniAction("⚙", tr("AI সেটআপ", "AI setup"), onOpenSettings)
                    }
                    if (state.answer.isNotBlank()) {
                        MiniAction("📋", tr("কপি", "Copy"), onCopy)
                    }
                    MiniAction("✨", tr("সাজাও", "Tidy up"), onRephrase, enabled = hasText && !state.busy)
                    MiniAction("💬", tr("জিজ্ঞাসা", "Ask AI"), onAsk, enabled = hasText && !state.busy)
                    Spacer(Modifier.width(2.dp))
                    // Tap = insert, long press = run as a command (v0.7.0).
                    Box(
                        Modifier.pointerInput(hasText) {
                            detectTapGestures(
                                onTap = { if (hasText) onInsert() },
                                onLongPress = { if (hasText) onCommand() },
                            )
                        },
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (hasText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (hasText) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                        ) {
                            Text(
                                "⏎",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAction(label: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/** Dot colour: red while the mic is hot, amber while paused, green when the text is ready. */
private fun DictationBubbleState.dotColor(): Color = when (phase) {
    DictationPhase.LISTENING, DictationPhase.PROCESSING -> Color(0xFFE53935)
    DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED -> Color(0xFFF9A825)
    DictationPhase.REVIEW, DictationPhase.SAVED, DictationPhase.INSERTED -> Color(0xFF2E7D32)
    else -> Color(0xFF90A4AE)
}

private fun DictationBubbleState.statusLabel(): String = when (phase) {
    DictationPhase.LISTENING -> tr("শুনছি… বলুন", "Listening — speak")
    DictationPhase.TEMP_PAUSE -> tr("থেমেছেন · শুনছি", "Pause · still listening")
    DictationPhase.PROCESSING -> tr("লিখছি…", "Writing…")
    DictationPhase.PAUSED -> tr("বিরতিতে", "Paused")
    DictationPhase.WAITING -> tr("মাইক বন্ধ", "Mic off")
    DictationPhase.INTERRUPTED -> tr("বাধা · আবার শুনছি", "Interrupted · listening again")
    DictationPhase.REVIEW, DictationPhase.SAVED, DictationPhase.INSERTED -> tr("লেখা প্রস্তুত", "Text ready")
    else -> tr("প্রস্তুত", "Ready")
}

private fun DictationBubbleState.placeholder(): String =
    if (busy) tr("শুনছি…", "Listening…") else tr("বলুন, লেখা এখানে আসবে…", "Speak — your words appear here…")
