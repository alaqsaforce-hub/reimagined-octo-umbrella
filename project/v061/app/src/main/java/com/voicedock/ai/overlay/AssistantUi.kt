package com.voicedock.ai.overlay

import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.context.CatchUp
import com.voicedock.ai.context.ContextAssessment
import com.voicedock.ai.voice.DictationPhase

enum class AssistantPhase { IDLE, SCANNING, CONTEXT_CARD, CHAT }

/** Everything the floating assistant renders. No Android types -> easy to reason about / test. */
data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val assessment: ContextAssessment? = null,
    val greeting: String = "",
    val catchUp: CatchUp? = null,
    val answer: String = "",
    val providerLabel: String = "",
    val draft: String = "",
    val draftOptions: List<String> = emptyList(),
    val note: String = "",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val localOnly: Boolean = true,
    val dictationPhase: DictationPhase? = null,
)

/**
 * v0.6.6 — state of the small dictation bubble (the first thing a tap opens).
 * Deliberately minimal: the bubble is only as tall as [liveText] plus one row of icons.
 */
data class DictationBubbleState(
    val visible: Boolean = false,
    val phase: DictationPhase? = null,
    /** Live recognised text, or the tidied version after ✨. */
    val liveText: String = "",
    val level: Float = 0f,
    val busy: Boolean = false,
    val busyLabel: String = "",
    /** Answer shown after 💬 (never inserted anywhere). */
    val answer: String = "",
    val providerLabel: String = "",
    val note: String = "",
)

/** Label for one action chip (bn/en). */
fun AssistantAction.label(bn: Boolean): String = when (this) {
    AssistantAction.DICTATE -> if (bn) "🎤 ডিক্টেশন" else "🎤 Dictate"
    AssistantAction.DRAFT_REPLY -> if (bn) "✍️ উত্তরের খসড়া" else "✍️ Draft reply"
    AssistantAction.INSERT_APPROVED -> if (bn) "এখানে বসাও" else "Insert here"
    AssistantAction.COPY -> if (bn) "📋 কপি" else "📋 Copy"
    AssistantAction.EXPLAIN_SCREEN -> if (bn) "🖼️ স্ক্রিন বুঝাও" else "🖼️ Explain screen"
    AssistantAction.SUMMARISE -> if (bn) "📄 সারাংশ" else "📄 Summarise"
    AssistantAction.ASK_QUESTION -> if (bn) "❓ প্রশ্ন করুন" else "❓ Ask a question"
    AssistantAction.REMEMBER -> if (bn) "🧠 মনে রাখো" else "🧠 Remember"
    AssistantAction.CATCH_UP -> if (bn) "⏱ কী ঘটেছে" else "⏱ What happened"
    AssistantAction.TODAY_TASKS -> if (bn) "✅ আজকের কাজ" else "✅ Today's tasks"
    AssistantAction.GENERAL_HELP -> if (bn) "💡 সাধারণ সাহায্য" else "💡 General help"
    AssistantAction.WHY_BLOCKED -> if (bn) "🔒 কেন পড়িনি?" else "🔒 Why not?"
    AssistantAction.OPEN_SETTINGS -> if (bn) "⚙️ সেটিংস" else "⚙️ Settings"
}
