package com.voicedock.ai.command

/**
 * v0.7.0 — what each intent needs, whether it can hurt, and whether this build can actually do it.
 *
 * [available] is the honest switch: a v0.9 intent is recognised and explained, but the pipeline refuses
 * to pretend it ran. When a phase lands, only this table changes.
 */
data class IntentMeta(
    val requiredSlots: Set<Slot>,
    val destructive: Boolean,
    val available: Boolean,
    val helpBn: String,
    val helpEn: String,
)

object CommandRegistry {

    private val registry: Map<Intent, IntentMeta> = mapOf(
        Intent.APP_LAUNCH to IntentMeta(setOf(Slot.APP), false, true, "অ্যাপ খোলা", "Open an app"),
        Intent.MEMORY_STORE to IntentMeta(setOf(Slot.TEXT), false, true, "কথা মনে রাখা", "Remember something"),
        Intent.MEMORY_RECALL to IntentMeta(emptySet(), false, true, "মনে রাখা কথা বলা", "Recall memories"),
        Intent.SPEAK to IntentMeta(setOf(Slot.TEXT), false, true, "জোরে পড়ে শোনানো", "Read aloud"),
        Intent.ASK_AI to IntentMeta(emptySet(), false, true, "AI-কে জিজ্ঞাসা", "Ask the AI"),
        // Recognised now, executed later — the pipeline explains instead of failing silently.
        Intent.CALL_PLACE to IntentMeta(setOf(Slot.CONTACT), true, false, "ফোন করা (v0.9.0)", "Place a call (v0.9.0)"),
        Intent.CALL_ANSWER to IntentMeta(emptySet(), true, false, "কল ধরা (v0.9.0)", "Answer a call (v0.9.0)"),
        Intent.CALL_DECLINE to IntentMeta(emptySet(), true, false, "কল কাটা (v0.9.0)", "Decline a call (v0.9.0)"),
        Intent.MSG_DIGEST to IntentMeta(emptySet(), false, false, "মেসেজের সারমর্ম (v0.8.0)", "Message digest (v0.8.0)"),
        Intent.MSG_REPLY to IntentMeta(setOf(Slot.CONTACT, Slot.TEXT), true, false, "মেসেজে উত্তর (v0.8.0)", "Reply to a message (v0.8.0)"),
        Intent.MSG_READ to IntentMeta(emptySet(), false, false, "মেসেজ পড়া (v0.8.0)", "Read messages (v0.8.0)"),
        Intent.MEDIA to IntentMeta(setOf(Slot.QUERY), false, false, "মিউজিক নিয়ন্ত্রণ (v0.10.0)", "Media control (v0.10.0)"),
        Intent.UNKNOWN to IntentMeta(setOf(Slot.QUERY), false, false, "বোঝা যায়নি", "Not understood"),
    )

    fun meta(i: Intent): IntentMeta = registry[i] ?: registry.getValue(Intent.UNKNOWN)

    fun isAvailable(i: Intent): Boolean = meta(i).available

    fun isDestructive(i: Intent): Boolean = meta(i).destructive

    /** Required slots that the command did not provide, in registry order. */
    fun missing(spec: CommandSpec): List<Slot> =
        meta(spec.intent).requiredSlots.filter { spec.slot(it).isNullOrBlank() }

    fun help(i: Intent, bengali: Boolean): String = meta(i).let { if (bengali) it.helpBn else it.helpEn }

    /** Intents the user may actually pick from when a sentence was not understood. */
    fun offered(bengali: Boolean): List<Intent> =
        listOf(Intent.APP_LAUNCH, Intent.MEMORY_STORE, Intent.MEMORY_RECALL, Intent.ASK_AI)

    fun all(bengali: Boolean): List<String> = entries().map { help(it, bengali) }

    fun entries(): List<Intent> = registry.keys.filter { it != Intent.UNKNOWN }
}
