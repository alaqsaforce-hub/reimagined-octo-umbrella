package com.voicedock.ai.v130

import com.voicedock.ai.agents.AgentTask
import com.voicedock.ai.agents.TaskBoard
import com.voicedock.ai.agents.TaskStatus
import com.voicedock.ai.agents.TaskStep
import com.voicedock.ai.agents.TerminalDecision
import com.voicedock.ai.agents.TerminalPolicy
import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.Slot
import com.voicedock.ai.device.ChatInstance
import com.voicedock.ai.device.ChatInstanceCore
import com.voicedock.ai.device.ChatKind
import com.voicedock.ai.device.SimCore
import com.voicedock.ai.device.SimInfo
import com.voicedock.ai.telecom.ContactEntry
import com.voicedock.ai.device.CallerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.0 — the parts of "device access" that can be proven without a phone: which SIM a sentence means,
 * which chat instance a sentence means, who a number is, what a shell command is allowed to do, and how a
 * background task is ordered. Everything here is pure Kotlin on purpose — these are the decisions that must
 * not depend on an OEM build.
 */
class SimCoreTest {

    private fun sim(slot: Int, carrier: String, number: String? = null, voice: Boolean = false, active: Boolean = true) =
        SimInfo(
            slot = slot, displaySlot = slot + 1, subscriptionId = 100 + slot, carrier = carrier,
            label = carrier, number = number, isDefaultVoice = voice, isDefaultData = false, active = active,
        )

    private val dual = listOf(sim(0, "Grameenphone", "01712345678"), sim(1, "Robi", "01987654321", voice = true))

    @Test fun spokenSlotIsUnderstoodInBothScriptsAndBothWordOrders() {
        assertEquals(2, SimCore.parseSlot("সিম ২ দিয়ে কল দাও"))
        assertEquals(2, SimCore.parseSlot("sim 2 diye call dao"))
        assertEquals(2, SimCore.parseSlot("২ নম্বর সিম"))
        assertEquals(2, SimCore.parseSlot("দ্বিতীয় সিম"))
        assertEquals(1, SimCore.parseSlot("প্রথম সিম"))
        assertEquals(null, SimCore.parseSlot("রহিমকে ফোন করো"))
    }

    @Test fun explicitSlotWinsOverTheSystemDefault() {
        // Asked for SIM 1 even though SIM 2 is the system default: the request wins.
        assertEquals(1, SimCore.pick(dual, 1)?.displaySlot)
        assertEquals(2, SimCore.pick(dual, 2)?.displaySlot)
        // No slot asked for → the system's default-call SIM (slot 2 here), never "the first one".
        assertEquals(2, SimCore.pick(dual, null)?.displaySlot)
    }

    @Test fun missingSimWithNoSubstitute() {
        assertNull(SimCore.pick(dual, 3))
        // …and that becomes a question naming what does exist, not a silent call on the wrong SIM.
        val msg = SimCore.explainMissing(dual, 3, bengali = true)
        assertTrue(msg, msg.contains("সিম ৩") && msg.contains("সিম ১") && msg.contains("সিম ২"))
    }

    @Test fun aCallTargetNeverContainsTheSimWords() {
        assertEquals("রহিমকে", SimCore.stripSimPhrase("সিম ২ দিয়ে রহিমকে"))
        assertEquals("Rahim", SimCore.stripSimPhrase("on sim 2 Rahim"))
        assertEquals("রহিমকে ফোন করো", SimCore.stripSimPhrase("রহিমকে ফোন করো"))
    }

    @Test fun ownNumbersAreMaskedAndExplainTheMissingPermission() {
        val text = SimCore.myNumbers(dual, bengali = true)
        assertTrue(text, text.contains("••••5678"))
        assertTrue("a full number must never be printed", !text.contains("01712345678"))
        val none = SimCore.myNumbers(listOf(sim(0, "GP"), sim(1, "Robi")), bengali = true)
        assertTrue(none, none.contains("READ_PHONE_NUMBERS"))
    }

    @Test fun inactiveSubscriptionsAreNeverOffered() {
        val oneOff = listOf(sim(0, "GP", voice = true), sim(1, "Robi", active = false))
        assertEquals(listOf(1), SimCore.usable(oneOff).map { it.displaySlot })
        assertNull(SimCore.pick(oneOff, 2))
    }
}

class ChatInstanceCoreTest {

    private val wa = ChatInstance(ChatKind.WHATSAPP, "com.whatsapp", 0, "WhatsApp", cloned = false)
    private val waBiz = ChatInstance(ChatKind.WHATSAPP_BUSINESS, "com.whatsapp.w4b", 0, "WhatsApp Business", cloned = false)
    private val waClone = ChatInstance(ChatKind.WHATSAPP, "com.whatsapp", 10, "Dual Messenger", cloned = true)
    private val messenger = ChatInstance(ChatKind.MESSENGER, "com.facebook.orca", 0, "Messenger", cloned = false)

    @Test fun everyProfileIsADistinctInstance() {
        val all = ChatInstanceCore.dedupe(listOf(wa, wa, waClone, messenger))
        assertEquals(listOf("com.whatsapp@0", "com.whatsapp@10", "com.facebook.orca@0"), all.map { it.key })
        assertEquals(2, ChatInstanceCore.whatsapps(all).size)
    }

    @Test fun aSpokenHintSelectsTheInstanceAndAmbiguityAsks() {
        val all = listOf(wa, waBiz, waClone)
        assertEquals(waBiz.key, ChatInstanceCore.pick(all, "বিজনেস হোয়াটসঅ্যাপ")?.key)
        assertEquals(waClone.key, ChatInstanceCore.pick(all, "ক্লোন করা হোয়াটসঅ্যাপ")?.key)
        assertEquals(wa.key, ChatInstanceCore.pick(all, "হোয়াটসঅ্যাপ")?.key)
        // The plain app is the default when the user only says "WhatsApp"; the clone never is.
        assertEquals(wa.key, ChatInstanceCore.pick(listOf(wa, waClone), "হোয়াটসঅ্যাপ খোলো")?.key)
        // Naming no app at all with two different apps installed is a genuine ambiguity → ask.
        assertNull(ChatInstanceCore.pick(listOf(wa, messenger), "চ্যাট খোলো"))
        // …but naming one of them resolves it.
        assertEquals(messenger.key, ChatInstanceCore.pick(listOf(wa, messenger), "মেসেঞ্জার খোলো")?.key)
    }

    @Test fun aCloneGetsNoDirectReplyPromise() {
        assertFalse(ChatInstanceCore.canDirectReply(waClone))
        assertTrue(ChatInstanceCore.canDirectReply(wa))
        assertTrue(waClone.title.contains("ক্লোন"))
    }

    @Test fun choicesAreShortAndNamed() {
        val names = ChatInstanceCore.choices(listOf(wa, waBiz, waClone, messenger))
        assertEquals(4, names.size)
        assertTrue(names.any { it.contains("Business") })
    }
}

class CallerIdentityTest {

    private val contacts = listOf(
        ContactEntry("রহিম", "+8801712345678"),
        ContactEntry("করিম", "01811111111"),
    )

    @Test fun localAndInternationalFormsAreTheSamePerson() {
        assertEquals(CallerIdentity.canonical("+8801712345678"), CallerIdentity.canonical("01712345678"))
        val id = CallerIdentity.of("01712345678", contacts)
        assertTrue(id.saved)
        assertEquals("রহিম", id.name)
    }

    @Test fun anUnknownNumberIsSaidToBeUnknown() {
        val id = CallerIdentity.of("01555000000", contacts)
        assertFalse(id.saved)
        assertEquals("01555000000", id.name)
        val said = CallerIdentity.incomingText(id, bengali = true)
        assertTrue(said, said.contains("সেভ করা নেই"))
    }

    @Test fun aSavedCallerIsNamedWithItsSavedStatus() {
        val id = CallerIdentity.of("+8801712345678", contacts)
        val said = CallerIdentity.incomingText(id, bengali = true)
        assertTrue(said, said.contains("রহিম") && said.contains("সেভ করা আছে"))
    }
}

class TerminalPolicyTest {

    @Test fun offByDefaultEvenForHarmlessCommands() {
        val v = TerminalPolicy.decide("ls", enabled = false, confirmed = true)
        assertEquals(TerminalDecision.BLOCK, v.decision)
        assertTrue(v.reasonBn.contains("সেটিংস"))
    }

    @Test fun readOnlyRunsWithoutAConfirmation() {
        assertEquals(TerminalDecision.ALLOW, TerminalPolicy.decide("ls -la", true, false).decision)
        assertEquals(TerminalDecision.ALLOW, TerminalPolicy.decide("termux-info", true, false).decision)
    }

    @Test fun changingThePhoneNeedsAConfirmationEveryTime() {
        val ask = TerminalPolicy.decide("pkg install python", enabled = true, confirmed = false)
        assertEquals(TerminalDecision.CONFIRM, ask.decision)
        assertTrue(ask.dangerous)
        assertEquals(TerminalDecision.ALLOW, TerminalPolicy.decide("pkg install python", true, true).decision)
    }

    @Test fun catastrophicCommandsAreRefusedEvenWhenConfirmed() {
        for (cmd in listOf("rm -rf /", "mkfs.ext4 /dev/block/sda", "dd if=/dev/zero of=/dev/block/sda", "reboot")) {
            assertEquals(cmd, TerminalDecision.BLOCK, TerminalPolicy.decide(cmd, enabled = true, confirmed = true).decision)
        }
    }

    @Test fun dispatchTextNeverClaimsTheCommandRan() {
        val text = TerminalPolicy.dispatchedText("ls", termuxPresent = true, bengali = true)
        assertTrue(text, text.contains("পাঠানো হলো"))
        val missing = TerminalPolicy.dispatchedText("ls", termuxPresent = false, bengali = true)
        assertTrue(missing, missing.contains("ইনস্টল"))
    }
}

class TaskBoardTest {

    private fun task(vararg steps: String) = AgentTask(
        id = "t1", title = "test", steps = steps.map { TaskStep(it) }, createdAt = 1L,
    )

    @Test fun stepsAreSplitOnWhatPeopleActuallySay() {
        assertEquals(listOf("মনে করাও পানি খেতে", "হোয়াটসঅ্যাপ খোলো"),
            TaskBoard.parseSteps("মনে করাও পানি খেতে; তারপর হোয়াটসঅ্যাপ খোলো"))
        assertEquals(listOf("open camera", "take a photo"), TaskBoard.parseSteps("open camera then take a photo"))
        assertTrue(TaskBoard.parseSteps("").isEmpty())
        assertTrue("a bare separator is not a step", TaskBoard.parseSteps("; ; ;").isEmpty())
    }

    @Test fun aTaskRunsOneStepAtATimeInOrder() {
        var t = task("ক খ", "খ গ", "গ ঘ")
        assertEquals("ক খ", TaskBoard.nextStep(t)?.command)
        t = TaskBoard.recordStep(t, "ক খ", ok = true, note = "ok")
        assertEquals("খ গ", TaskBoard.nextStep(t)?.command)
        assertEquals(1, t.done)
        assertTrue(t.copy(steps = t.steps.map { it.copy(status = TaskStatus.DONE) }).copy(status = TaskStatus.RUNNING).progress > 0f)
    }

    @Test fun aFailingStepIsRetriedOnceThenTheTaskFails() {
        var t = task("ক খ")
        t = TaskBoard.recordStep(t, "ক খ", ok = false, note = "বাধা")
        assertEquals(TaskStatus.RUNNING, t.status)
        assertTrue(t.steps.first().note.startsWith("retry"))
        t = TaskBoard.recordStep(t, "ক খ", ok = false, note = "আবার বাধা")
        assertEquals(TaskStatus.FAILED, t.status)
        assertTrue(t.lastError.isNotBlank())
    }

    @Test fun cancellingStopsQueuedSteps() {
        val t = TaskBoard.cancel(task("ক", "খ"))
        assertEquals(TaskStatus.CANCELLED, t.status)
        assertTrue(t.steps.all { it.status == TaskStatus.CANCELLED })
        assertNull(TaskBoard.nextStep(t))
    }

    @Test fun theBoardSurvivesARestart() {
        val tasks = listOf(task("ক খ", "খ গ").copy(status = TaskStatus.RUNNING, attempts = 2, lastError = "x"))
        val back = TaskBoard.decode(TaskBoard.encode(tasks))
        assertEquals(1, back.size)
        assertEquals(tasks[0].id, back[0].id)
        assertEquals(listOf("ক খ", "খ গ"), back[0].steps.map { it.command })
        assertEquals(TaskStatus.RUNNING, back[0].status)
        assertEquals(2, back[0].attempts)
        assertEquals("x", back[0].lastError)
    }

    @Test fun garbageOnDiskDoesNotCrashTheBoard() {
        assertTrue(TaskBoard.decode("not json").isEmpty())
        assertTrue(TaskBoard.decode("").isEmpty())
    }

    @Test fun addKeepsTheNewestTasksAndRejectsAnEmptyOne() {
        var list: List<AgentTask> = emptyList()
        var made: AgentTask? = null
        val (after, none) = TaskBoard.add(list, "শিরোনাম", emptyList(), 5L, "t0")
        assertTrue(after.isEmpty())
        assertNull(none)
        val (after2, made2) = TaskBoard.add(list, "", listOf("এক"), 5L, "t1")
        made = made2
        list = after2
        assertEquals(1, list.size)
        assertNotNull(made)
        assertEquals("এক", list[0].title)
    }
}

/** The sentences the user actually speaks, through the real offline parser. */
class DeviceCommandParsingTest {

    @Test fun callOnTheSecondSimKeepsTheNameAndTheSlot() {
        val spec = CommandParser.offline("সিম ২ দিয়ে রহিমকে ফোন করো")
        assertEquals(Intent.CALL_PLACE, spec.intent)
        assertEquals("2", spec.slot(Slot.SIM))
        assertEquals("রহিমকে", spec.slot(Slot.CONTACT))
    }

    @Test fun myNumberAsksAndWhoIsCallingAsks() {
        assertEquals(Intent.SIM_NUMBERS, CommandParser.offline("আমার নম্বর কত").intent)
        assertEquals(Intent.SIM_NUMBERS, CommandParser.offline("কয়টা সিম আছে").intent)
        assertEquals(Intent.CALLER_WHO, CommandParser.offline("কে ফোন করছে").intent)
    }

    @Test fun chatInstancesAndTermuxAreRecognised() {
        val chat = CommandParser.offline("বিজনেস হোয়াটসঅ্যাপ খোলো")
        assertEquals(Intent.CHAT_OPEN, chat.intent)
        val termux = CommandParser.offline("টার্মাক্সে ls চালাও")
        assertEquals(Intent.TERMUX_RUN, termux.intent)
        assertEquals("ls", termux.slot(Slot.TEXT))
        // "টার্মাক্স খোলো" is opening the app, not running a shell.
        assertEquals(Intent.APP_LAUNCH, CommandParser.offline("টার্মাক্স খোলো").intent)
    }

    @Test fun aSpokenTaskBecomesSteps() {
        val spec = CommandParser.offline("কাজ দাও: হোয়াটসঅ্যাপ খোলো; তারপর ক্যামেরা খোলো")
        assertEquals(Intent.TASK_ADD, spec.intent)
        assertEquals(2, TaskBoard.parseSteps(spec.slot(Slot.TEXT).orEmpty()).size)
        assertEquals(Intent.TASK_LIST, CommandParser.offline("কাজের তালিকা দেখাও").intent)
    }

    @Test fun everyNewIntentIsRegisteredAndAvailable() {
        val new = listOf(
            Intent.SIM_NUMBERS, Intent.CALLER_WHO, Intent.CHAT_OPEN, Intent.TERMUX_RUN,
            Intent.TASK_ADD, Intent.TASK_LIST, Intent.TASK_CANCEL,
        )
        new.forEach {
            assertTrue("$it must be listed", CommandRegistry.entries().contains(it))
            assertTrue("$it must be executable", CommandRegistry.isAvailable(it))
            assertTrue("$it needs a help line", CommandRegistry.help(it, true).isNotBlank())
        }
        // The ones that can change the phone are marked destructive, so the pipeline confirms them.
        assertTrue(CommandRegistry.isDestructive(Intent.TERMUX_RUN))
        assertTrue(CommandRegistry.isDestructive(Intent.TASK_ADD))
        assertFalse(CommandRegistry.isDestructive(Intent.SIM_NUMBERS))
    }
}
