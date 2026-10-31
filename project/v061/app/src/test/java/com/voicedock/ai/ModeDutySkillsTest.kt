package com.voicedock.ai

import com.voicedock.ai.assistant.AssistantMode
import com.voicedock.ai.assistant.AssistantModeCore
import com.voicedock.ai.hotword.DutyPlanner
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.skills.SkillGroup
import com.voicedock.ai.skills.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.0 — the three pure decisions behind "always an agent, speaks when called, cheap on the battery":
 *  * [AssistantModeCore] — what each of the four modes may do;
 *  * [DutyPlanner] — how often the microphone may open;
 *  * [Skills] — the honest list of what works today.
 */
class ModeDutySkillsTest {

    // ---------------------------------------------------------------- modes

    @Test fun textOnlyKeepsTheMicrophoneClosed() {
        assertFalse(AssistantModeCore.acceptsVoice(AssistantMode.TEXT_ONLY))
        assertFalse(AssistantModeCore.allowsHotword(AssistantMode.TEXT_ONLY))
        assertFalse(AssistantModeCore.greetsAloud(AssistantMode.TEXT_ONLY))
        assertFalse(AssistantModeCore.speaksResults(AssistantMode.TEXT_ONLY, speakResultsEnabled = true))
        assertEquals(AssistantModeCore.WakeAction.SHOW_ONLY, AssistantModeCore.onWakeAction(AssistantMode.TEXT_ONLY))
        // …and every other mode may listen.
        listOf(AssistantMode.AGENT, AssistantMode.VOICE_TO_VOICE, AssistantMode.VOICE_TEXT).forEach {
            assertTrue(it.name, AssistantModeCore.acceptsVoice(it))
            assertEquals(it.name, AssistantModeCore.WakeAction.LISTEN_AND_GREET, AssistantModeCore.onWakeAction(it))
        }
    }

    @Test fun theThreeVoiceModesDifferInWhatTheyShowAndSay() {
        // voice → voice: speech both ways, the transcript is secondary
        assertFalse(AssistantModeCore.showsText(AssistantMode.VOICE_TO_VOICE))
        // voice + text: both
        assertTrue(AssistantModeCore.showsText(AssistantMode.VOICE_TEXT))
        assertTrue(AssistantModeCore.speaksResults(AssistantMode.VOICE_TEXT, true))
        // the "Speak results" switch still has the final word
        assertFalse(AssistantModeCore.speaksResults(AssistantMode.AGENT, speakResultsEnabled = false))
        assertTrue(AssistantModeCore.speaksResults(AssistantMode.AGENT, speakResultsEnabled = true))
    }

    @Test fun onlyAgentModeMayRunAPlan() {
        assertTrue(AssistantModeCore.allowsPlans(AssistantMode.AGENT))
        assertFalse(AssistantModeCore.allowsPlans(AssistantMode.VOICE_TEXT))
        assertFalse(AssistantModeCore.allowsPlans(AssistantMode.VOICE_TO_VOICE))
        assertFalse(AssistantModeCore.allowsPlans(AssistantMode.TEXT_ONLY))
    }

    @Test fun everyModeHasATitleAndADescriptionInBothLanguages() {
        AssistantMode.entries.forEach { m ->
            assertTrue(m.titleBn.isNotBlank())
            assertTrue(m.titleEn.isNotBlank())
            assertTrue(AssistantModeCore.describe(m, bengali = true).isNotBlank())
            assertTrue(AssistantModeCore.describe(m, bengali = false).isNotBlank())
        }
    }

    @Test fun theDefaultIsAgentWithLeanListening() {
        val s = AppSettings()
        assertEquals(AssistantMode.AGENT, s.mode)
        assertTrue("the wake word still defaults to off", !s.hotwordEnabled)
        assertTrue("lean listening is the default", s.hotwordLean)
    }

    // ---------------------------------------------------------------- duty cycle

    @Test fun chargingListensContinuously() {
        val d = DutyPlanner.plan(screenOn = false, charging = true, alwaysOn = false, lean = true)
        assertEquals(DutyPlanner.CONTINUOUS_MS, d.idleMs)
        assertTrue(d.labelEn.contains("Charging"))
    }

    @Test fun screenOffInLeanModeIsTheCheapest() {
        val lean = DutyPlanner.plan(screenOn = false, charging = false, alwaysOn = true, lean = true)
        val full = DutyPlanner.plan(screenOn = false, charging = false, alwaysOn = true, lean = false)
        val idle = DutyPlanner.plan(screenOn = false, charging = false, alwaysOn = false, lean = true)
        assertTrue("lean must be the longest gap between listen cycles", lean.idleMs > full.idleMs)
        // With always-on off the microphone does not open at all; the poll only watches for the screen
        // coming back, so its interval says nothing about battery — what matters is that it is not listening.
        assertTrue(idle.labelEn.contains("not listening"))
        assertTrue(idle.labelBn.contains("শুনছে না"))
        // A wake word is not lost for ever: even the leanest plan comes back within ~10 s.
        assertTrue(lean.idleMs <= 10_000L)
    }

    @Test fun theDutyPercentageIsQuotedHonestly() {
        val lean = DutyPlanner.plan(false, false, true, true)
        val pct = DutyPlanner.dutyPercent(lean, listenMs = 1_000)
        assertTrue("lean duty must be small", pct in 1..15)
        val charging = DutyPlanner.plan(false, true, false, true)
        assertTrue("charging duty must be high", DutyPlanner.dutyPercent(charging, 1_000) > 60)
    }

    // ---------------------------------------------------------------- skills

    @Test fun skillIdsAreUniqueAndEveryRowIsDescribed() {
        val ids = Skills.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Skills.all().forEach {
            assertTrue(it.id, it.nameBn.isNotBlank())
            assertTrue(it.id, it.nameEn.isNotBlank())
        }
        assertEquals(SkillGroup.entries.toSet(), Skills.byGroup().keys)
    }

    @Test fun everyShippedSkillIsMarkedReadyAndEveryPlannedOneSaysWhen() {
        // The skills that exist in the code must be marked ready…
        listOf("call.place", "call.answer", "msg.digest", "msg.reply", "media.control", "app.launch",
            "memory.store", "memory.recall", "ask.ai", "undo", "local.model", "github", "mail", "drive").forEach {
            assertEquals(it, true, Skills.of(it)?.ready)
        }
        // …and nothing planned may be listed without saying which version or what is missing.
        Skills.planned().forEach {
            assertTrue(it.id, it.noteBn.isNotBlank())
            assertTrue(it.id, it.noteEn.isNotBlank())
        }
        assertNotNull(Skills.of("mcp.client"))
        assertFalse(Skills.of("mcp.client")!!.ready)
    }

    @Test fun theSummaryCountsRealSkills() {
        val summary = Skills.summary(bengali = true)
        assertTrue(summary.contains("${Skills.ready().size} / ${Skills.all().size}"))
        assertTrue(Skills.ready().isNotEmpty())
    }
}
