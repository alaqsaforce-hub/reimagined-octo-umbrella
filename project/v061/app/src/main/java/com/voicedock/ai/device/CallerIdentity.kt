package com.voicedock.ai.device

import com.voicedock.ai.telecom.ContactEntry
import com.voicedock.ai.telecom.ContactMatch

/**
 * v1.3.0 — "যে কল আসছে/যাকে কল দেব সে কে? নাম কন্টাক্টে সেভ আছে কি?"
 *
 * Pure: the caller supplies the phone's contact list and the SIM list. This is what lets the assistant say
 * *"রহিম (সেভ করা আছে) — সিম ২-এ কল আসছে"* instead of *"unknown number"*, and what makes "মাকে ফোন দাও"
 * resolve to a saved contact rather than a guess.
 */
data class Identity(
    /** Best display name: the saved contact name, else the raw number. */
    val name: String,
    val number: String,
    val saved: Boolean,
    /** Other numbers of the same contact (a second SIM, an office line…). */
    val alternates: List<String> = emptyList(),
    /** Which SIM the number belongs to, when the OS told us (incoming calls do). */
    val sim: SimInfo? = null,
) {
    val title: String get() = if (saved) name else number
}

object CallerIdentity {

    /** Digits only, with the last 10 kept, so "+8801712345678" and "01712345678" match. */
    fun canonical(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /** Resolve a number (incoming call) to a saved contact. Null name = not in the phone book. */
    fun of(number: String, contacts: List<ContactEntry>, sim: SimInfo? = null): Identity {
        val key = canonical(number)
        val matches = contacts.filter { canonical(it.number) == key }
        if (matches.isEmpty()) return Identity(number.trim(), number.trim(), saved = false, sim = sim)
        val best = matches.first()
        return Identity(
            name = best.name,
            number = number.trim(),
            saved = true,
            alternates = matches.map { it.number }.filter { canonical(it) != key }.distinct().take(3),
            sim = sim,
        )
    }

    /** "কাকে?" — the same fuzzy match the call command already uses, now with identity information attached. */
    fun find(query: String, contacts: List<ContactEntry>): List<Identity> =
        ContactMatch.match(query, contacts, limit = 4).distinctBy { canonical(it.number) }.map { c ->
            Identity(
                name = c.name,
                number = c.number,
                saved = true,
                alternates = contacts.filter { it.name == c.name }.map { it.number }.filter { canonical(it) != canonical(c.number) }.take(3),
            )
        }

    /** One spoken sentence for the bubble / TTS. */
    fun describe(id: Identity, bengali: Boolean): String = if (bengali) {
        buildString {
            if (id.saved) append("${id.name} (কন্টাক্টে সেভ করা আছে)")
            else append("${id.number} — কন্টাক্টে সেভ করা নেই")
            id.sim?.let { append(" · ${it.title}") }
        }
    } else {
        buildString {
            if (id.saved) append("${id.name} (saved contact)") else append("${id.number} — not in contacts")
            id.sim?.let { append(" · ${it.title}") }
        }
    }

    /** For a missed/ringing call: who is it and is it worth interrupting for? */
    fun incomingText(id: Identity, bengali: Boolean): String = if (bengali) {
        val who = if (id.saved) "${id.name} ফোন করছে (কন্টাক্টে সেভ করা আছে)"
        else "অপরিচিত নম্বর ${id.number} ফোন করছে (সেভ করা নেই)"
        who + (id.sim?.let { " · ${it.title}" } ?: "")
    } else {
        val who = if (id.saved) "${id.name} is calling (saved contact)"
        else "unknown number ${id.number} is calling (not saved)"
        who + (id.sim?.let { " · ${it.title}" } ?: "")
    }

    /**
     * "আমার নম্বর থেকে কে কল দিতে পারে" — the honest ordering used when a call must be placed:
     * a requested SIM wins, then the system default, then the first active one.
     */
    fun simFor(id: Identity, sims: List<SimInfo>, requestedSlot: Int?): SimInfo? =
        SimCore.pick(sims, requestedSlot) ?: id.sim
}
