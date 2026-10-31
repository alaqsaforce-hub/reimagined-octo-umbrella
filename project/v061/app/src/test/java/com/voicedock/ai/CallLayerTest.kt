package com.voicedock.ai

import com.voicedock.ai.command.CommandDecision
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.CommandSpec
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.notify.CallNotificationAction
import com.voicedock.ai.telecom.ContactEntry
import com.voicedock.ai.telecom.ContactMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0 — the rules behind "মাকে ফোন দাও", "ধরো", "কেটে দাও", before any Android call is attempted.
 */
class CallLayerTest {

    private val contacts = listOf(
        ContactEntry("মা", "+8801711111111"),
        ContactEntry("মা (অফিস)", "+8801722222222"),
        ContactEntry("Rahim Uddin", "+8801733333333"),
        ContactEntry("Karim", "+8801744444444"),
    )

    // ---------------------------------------------------------------- contacts

    @Test
    fun `exact bangla name wins`() {
        val hits = ContactMatch.match("মা", contacts)
        assertEquals("মা", hits.first().name)
        assertEquals("+8801711111111", hits.first().number)
    }

    @Test
    fun `bangla object marker is tolerated`() {
        assertTrue(ContactMatch.match("মাকে", contacts).any { it.name == "মা" })
    }

    @Test
    fun `two matching contacts both come back so the caller must ask`() {
        val hits = ContactMatch.match("মা", contacts)
        assertEquals(2, hits.size)
    }

    @Test
    fun `latin partial names match`() {
        assertTrue(ContactMatch.match("rah", contacts).any { it.name == "Rahim Uddin" })
        assertTrue(ContactMatch.match("karim", contacts).any { it.name == "Karim" })
    }

    @Test
    fun `unknown or blank names match nothing`() {
        assertTrue(ContactMatch.match("nobody", contacts).isEmpty())
        assertTrue(ContactMatch.match("", contacts).isEmpty())
        assertTrue(ContactMatch.match("   ", contacts).isEmpty())
    }

    @Test
    fun `number sanitising accepts real numbers and rejects junk`() {
        assertEquals("+8801711111111", ContactMatch.sanitizeNumber("+880 1711-111111"))
        assertEquals("01711111111", ContactMatch.sanitizeNumber("01711 111 111"))
        assertNull(ContactMatch.sanitizeNumber("123"))       // too short
        assertNull(ContactMatch.sanitizeNumber(""))
        assertNull(ContactMatch.sanitizeNumber("কল কর"))     // no digits at all
        assertNull(ContactMatch.sanitizeNumber("1".repeat(16))) // too long
    }

    @Test
    fun `aliases map a spoken word to a real name`() {
        val aliases = mapOf("আম্মু" to "মা", "mom" to "মা")
        assertEquals("মা", ContactMatch.expandAliases("আম্মু", aliases))
        assertEquals("মা", ContactMatch.expandAliases("Mom", aliases))
        assertEquals("Karim", ContactMatch.expandAliases("Karim", aliases))
    }

    // ---------------------------------------------------------------- notification answer buttons

    @Test
    fun `answer and decline titles are recognised in both languages`() {
        assertTrue(CallNotificationAction.isAnswerTitle("Answer"))
        assertTrue(CallNotificationAction.isAnswerTitle("উত্তর দিন"))
        assertTrue(CallNotificationAction.isDeclineTitle("Decline"))
        assertTrue(CallNotificationAction.isDeclineTitle("কেটে দিন"))
        assertFalse(CallNotificationAction.isAnswerTitle("Decline"))
        assertFalse(CallNotificationAction.isDeclineTitle("Answer"))
        assertFalse(CallNotificationAction.isAnswerTitle(null))
    }

    @Test
    fun `a generic notification title is not mistaken for a call button`() {
        assertFalse(CallNotificationAction.isAnswerTitle("Reply"))
        assertFalse(CallNotificationAction.isDeclineTitle("Mark as read"))
    }

    // ---------------------------------------------------------------- parsing + policy

    @Test
    fun `call sentences parse`() {
        val place = CommandParser.offline("মাকে ফোন কর")
        assertEquals(Intent.CALL_PLACE, place.intent)
        assertTrue(place.slot(Slot.CONTACT)!!.isNotBlank())

        assertEquals(Intent.CALL_ANSWER, CommandParser.offline("ধরো").intent)
        assertEquals(Intent.CALL_ANSWER, CommandParser.offline("answer it").intent)
        assertEquals(Intent.CALL_DECLINE, CommandParser.offline("কেটে দাও").intent)
        assertEquals(Intent.CALL_DECLINE, CommandParser.offline("reject the call").intent)
    }

    @Test
    fun `a number spoken in the sentence is kept`() {
        val spec = CommandParser.offline("০১৭১১১১১১১১ নম্বরে ফোন কর")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        // Bengali digits are mapped 1:1, so the slot holds a dialable string.
        assertTrue(spec.slot(Slot.CONTACT)!!.any { it.isDigit() })
    }

    @Test
    fun `calls are available in this build`() {
        assertTrue(CommandRegistry.isAvailable(Intent.CALL_PLACE))
        assertTrue(CommandRegistry.isAvailable(Intent.CALL_ANSWER))
        assertTrue(CommandRegistry.isAvailable(Intent.CALL_DECLINE))
    }

    @Test
    fun `placing and declining confirm, answering does not`() {
        assertTrue(CommandRegistry.isDestructive(Intent.CALL_PLACE))
        assertTrue(CommandRegistry.isDestructive(Intent.CALL_DECLINE))
        assertFalse(CommandRegistry.isDestructive(Intent.CALL_ANSWER))
    }

    @Test
    fun `answering a ringing call executes immediately`() {
        assertEquals(CommandDecision.Execute, CommandPipeline.decide(CommandSpec.of(Intent.CALL_ANSWER, "ধরো", 0.9), bengali = false))
    }

    @Test
    fun `placing a call asks first and shows the number`() {
        val d = CommandPipeline.decide(
            CommandSpec.of(Intent.CALL_PLACE, "call mom", 0.9, Slot.CONTACT to "+8801711111111"),
            bengali = false,
        )
        assertTrue(d is CommandDecision.Confirm)
        assertTrue((d as CommandDecision.Confirm).question.contains("+8801711111111"))
    }

    @Test
    fun `a call with no contact yet asks instead of dialling`() {
        val d = CommandPipeline.decide(CommandSpec.of(Intent.CALL_PLACE, "call", 0.9), bengali = true)
        assertTrue(d is CommandDecision.Clarify)
    }

    @Test
    fun `media without an action asks which action`() {
        // v0.10.0: media is available, so a vague request now asks about the action itself.
        val d = CommandPipeline.decide(CommandSpec.of(Intent.MEDIA, "music", 0.9), bengali = false)
        assertTrue(d is CommandDecision.Clarify)
        assertTrue((d as CommandDecision.Clarify).question.contains("music"))
    }
}
