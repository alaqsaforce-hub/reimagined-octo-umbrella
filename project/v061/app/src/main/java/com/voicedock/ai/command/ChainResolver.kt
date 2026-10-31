package com.voicedock.ai.command

/**
 * v0.10.0 — command chains: "শেষ যে মেসেজ দিয়েছে তাকে ফোন করো" /
 * "call the last person who messaged me".
 *
 * A chain is deliberately NOT a new intent: the second half of the sentence is an ordinary command
 * ([Intent.CALL_PLACE], [Intent.MSG_REPLY]) whose recipient the phone can work out itself. So the
 * resolver only replaces a pronoun in the contact slot with a real, visible name — the confirmation card
 * then shows exactly who will be called, and nothing is guessed.
 *
 * Everything here is pure: the caller supplies the senders it can see (newest activity first).
 */
sealed interface ChainOutcome {
    /** No pronoun in the sentence — the normal path runs unchanged. */
    data object NotAChain : ChainOutcome

    /** The recipient a pronoun resolved to, and the spec with the real name filled in. */
    data class Resolved(val spec: CommandSpec, val name: String) : ChainOutcome

    /** The chain was understood but there is nothing to chain to (no captured messages). */
    data class NoSenders(val spec: CommandSpec, val reason: String) : ChainOutcome
}

object ChainResolver {

    /** Bangla + English ways of saying "the person I last heard from". */
    private val pronouns = listOf(
        "শেষ যে মেসেজ", "শেষ যিনি মেসেজ", "সর্বশেষ যে মেসেজ", "শেষ মেসেজ দিয়েছে", "শেষ মেসেজ করছে",
        "যে শেষে মেসেজ", "শেষ যে কথা বলেছে", "সর্বশেষ প্রেরক",
        "last person who messaged", "last person that messaged", "last sender", "the last sender",
        "whoever messaged last", "who messaged last", "last person who texted", "the last person",
    )

    /** Intents that can be chained — the rest have no recipient to resolve. */
    private val chainable = setOf(Intent.CALL_PLACE, Intent.MSG_REPLY)

    /** Lower-case, punctuation → space, spacing collapsed. Length is not needed here. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun isPronoun(contact: String?): Boolean {
        val c = normalize(contact.orEmpty())
        if (c.isBlank()) return false
        return pronouns.any { c.contains(normalize(it)) }
    }

    /** True when this spec is a chain waiting for its recipient. */
    fun isChain(spec: CommandSpec): Boolean =
        spec.intent in chainable && isPronoun(spec.slot(Slot.CONTACT))

    /**
     * @param senders senders with captured notifications, newest activity first
     *                ([com.voicedock.ai.notify.NotifStore.sendersSince]).
     */
    fun resolve(spec: CommandSpec, senders: List<String>): ChainOutcome {
        if (!isChain(spec)) return ChainOutcome.NotAChain
        val first = senders.firstOrNull { it.isNotBlank() }
            ?: return ChainOutcome.NoSenders(spec, "no_senders")
        return ChainOutcome.Resolved(
            spec.copy(slots = spec.slots + (Slot.CONTACT to first)),
            first,
        )
    }

    /** The line shown while the chain is resolving, so the user sees who was picked. */
    fun announced(spec: CommandSpec, name: String, bengali: Boolean): String = when (spec.intent) {
        Intent.CALL_PLACE ->
            if (bengali) "শেষ মেসেজ দিয়েছেন ‘$name’ — কল করা হচ্ছে।" else "‘$name’ messaged last — calling them."
        Intent.MSG_REPLY ->
            if (bengali) "শেষ মেসেজ দিয়েছেন ‘$name’ — উত্তর পাঠাচ্ছি।" else "‘$name’ messaged last — sending the reply."
        else -> name
    }
}
