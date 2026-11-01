package com.voicedock.ai.v120

import com.voicedock.ai.command.CommandParser
import com.voicedock.ai.command.CommandPipeline
import com.voicedock.ai.command.CommandRegistry
import com.voicedock.ai.command.Intent
import com.voicedock.ai.command.ReminderSpeech
import com.voicedock.ai.command.Slot
import com.voicedock.ai.permissions.Access
import com.voicedock.ai.permissions.Feature
import com.voicedock.ai.permissions.ManifestAudit
import com.voicedock.ai.permissions.PermissionCatalog
import com.voicedock.ai.permissions.PermissionGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * v1.2.0 — the two structural promises of this version:
 *  1. no permission in the manifest is unaccounted for (and no catalog row claims a permission that is
 *     not declared — that would be a button that can never be granted);
 *  2. every feature knows exactly which accesses it needs, so "why is X not working" has one answer.
 */
class PermissionCatalogTest {

    private fun manifest(): String? {
        val candidates = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        return candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
    }

    @Test fun manifestAndCatalogAgreeInBothDirections() {
        val xml = manifest()
        assumeTrue("unit tests run outside the module dir — manifest audit skipped", xml != null)
        val declared = ManifestAudit.declaredFrom(xml!!)
        assertTrue("no permissions parsed from the manifest", declared.size > 5)
        val report = ManifestAudit.report(declared)
        assertTrue(report.describe(), report.clean)

        // The permissions this version added must really be there.
        assertTrue(declared.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertTrue(declared.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test fun parserFindsEveryUsesPermissionLine() {
        val xml = """
            <manifest>
              <uses-permission android:name="android.permission.RECORD_AUDIO" />
              <uses-permission
                  android:name="android.permission.POST_NOTIFICATIONS" />
            </manifest>
        """.trimIndent()
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO"),
            ManifestAudit.declaredFrom(xml),
        )
    }

    @Test fun auditReportsBothKindsOfGap() {
        val report = ManifestAudit.report(
            listOf("android.permission.RECORD_AUDIO", "android.permission.SEND_SMS"),
        )
        assertFalse(report.clean)
        assertEquals(listOf("android.permission.SEND_SMS"), report.unclassified)
        assertTrue("every other catalog permission is reported as undeclared", report.undeclared.size > 5)
    }

    @Test fun everyRowExplainsItselfInBothLanguages() {
        PermissionCatalog.items.forEach { item ->
            assertTrue("${item.key} needs a Bengali title", item.titleBn.any { it in '\u0980'..'\u09FF' })
            assertTrue("${item.key} needs an English title", item.titleEn.isNotBlank())
            assertTrue("${item.key} needs a reason", item.whyBn.isNotBlank() && item.whyEn.isNotBlank())
            assertTrue("${item.key} must unlock a feature", item.features.isNotEmpty())
            item.features.forEach { assertTrue("unknown feature $it", it in Feature.all) }
        }
        // Every feature must be covered by at least one access, otherwise it is silently unreachable.
        Feature.all.forEach { f ->
            assertTrue("no permission unlocks $f", PermissionCatalog.forFeature(f).isNotEmpty())
        }
    }

    @Test fun aFeatureIsBlockedUntilAllOfItsAccessesAreGranted() {
        val needs = PermissionCatalog.forFeature(Feature.REMINDERS).filter { it.access != Access.NORMAL }
        assertTrue("reminders need at least notifications + exact alarms", needs.size >= 2)
        val granted = needs.dropLast(1).map { it.key }.toMutableSet()
        assertFalse("one missing access still blocks the feature", PermissionGate.ready(Feature.REMINDERS) { it in granted })
        granted += needs.last().key
        assertTrue(PermissionGate.ready(Feature.REMINDERS) { it in granted })

        // Calls need every one of their accesses, not just CALL_PHONE.
        val callNeeds = PermissionCatalog.forFeature(Feature.CALLS).filter { it.access != Access.NORMAL }
        val onlyCall = mutableSetOf("android.permission.CALL_PHONE")
        assertFalse(PermissionGate.ready(Feature.CALLS) { it in onlyCall })
        val everything = callNeeds.map { it.key }.toSet()
        assertTrue(PermissionGate.ready(Feature.CALLS) { it in everything })
    }

    @Test fun notificationPermissionIsOnlyRequestedFromAndroid13() {
        val sdk12 = PermissionGate.requestable({ false }, 32)
        assertFalse(sdk12.contains("android.permission.POST_NOTIFICATIONS"))
        val sdk13 = PermissionGate.requestable({ false }, 33)
        assertTrue(sdk13.contains("android.permission.POST_NOTIFICATIONS"))
        // Nothing that is granted at install time is ever put in a dialog.
        assertFalse(sdk13.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(sdk13.contains("android.permission.INTERNET"))
    }

    @Test fun grantingEverythingClearsEveryBlockerAndTheSummaryIsHonest() {
        assertTrue(PermissionGate.blockedFeatures { false }.isNotEmpty())
        assertTrue(PermissionGate.blockedFeatures { true }.isEmpty())
        val empty = PermissionGate.summary { false }
        assertEquals(0, empty.granted)
        assertTrue(empty.total >= 10)
        assertTrue(PermissionGate.nextStep({ false }, bengali = true).any { it in '\u0980'..'\u09FF' })
        assertTrue(PermissionGate.nextStep({ true }, bengali = false).contains("✓"))
        assertEquals(PermissionCatalog.actionable.size, PermissionCatalog.actionableCount)
        assertTrue(PermissionCatalog.items.any { it.access == Access.NORMAL })
    }
}

/**
 * v1.2.0 — "৩টায় মনে করিয়ে দাও পানি খেতে" must become a reminder without a model, and a missing time or
 * goal must produce a question instead of a broken reminder.
 */
class ReminderSpeechTest {

    @Test fun goalAfterTheVerbAndTimeAreSeparated() {
        val spec = CommandParser.offline("৩টায় মনে করিয়ে দাও পানি খেতে")
        assertEquals(Intent.REMINDER_ADD, spec.intent)
        assertEquals("পানি খেতে", spec.slot(Slot.TEXT))
        assertNotNull("the time was not recognised", spec.slot(Slot.TIME))
    }

    @Test fun goalBeforeTheVerbStillWorks() {
        val spec = CommandParser.offline("পানি খেতে ৩টায় মনে করিয়ে দাও")
        assertEquals(Intent.REMINDER_ADD, spec.intent)
        assertTrue(spec.slot(Slot.TEXT)!!.contains("পানি"))
        assertNotNull(spec.slot(Slot.TIME))
    }

    @Test fun englishPhrasingWorksToo() {
        val spec = CommandParser.offline("remind me to call Rahim in 20 minutes")
        assertEquals(Intent.REMINDER_ADD, spec.intent)
        assertTrue(spec.slot(Slot.TEXT)!!.contains("Rahim"))
        assertNotNull(spec.slot(Slot.TIME))
    }

    @Test fun listRequestIsRecognised() {
        assertEquals(Intent.REMINDER_LIST, CommandParser.offline("আমার রিমাইন্ডার দেখাও").intent)
        assertEquals(Intent.REMINDER_LIST, CommandParser.offline("show my reminders").intent)
    }

    @Test fun missingTimeAsksInsteadOfGuessing() {
        val spec = CommandParser.offline("মনে করিয়ে দাও পানি খেতে")
        assertEquals(Intent.REMINDER_ADD, spec.intent)
        assertEquals("পানি খেতে", spec.slot(Slot.TEXT))
        assertEquals(null, spec.slot(Slot.TIME))
        val decision = CommandPipeline.decide(spec, bengali = true)
        assertTrue(decision is com.voicedock.ai.command.CommandDecision.Clarify)
        assertTrue((decision as com.voicedock.ai.command.CommandDecision.Clarify).question.contains("কখন"))
    }

    @Test fun missingGoalAsksForTheGoal() {
        val spec = CommandParser.offline("৩টায় মনে করিয়ে দাও")
        val decision = CommandPipeline.decide(spec, bengali = true)
        assertTrue(decision is com.voicedock.ai.command.CommandDecision.Clarify)
        assertTrue((decision as com.voicedock.ai.command.CommandDecision.Clarify).question.contains("কী"))
    }

    @Test fun aCompleteReminderIsExecutedWithoutConfirmation() {
        val spec = CommandParser.offline("কাল সকাল ৯টায় মনে করিয়ে দাও ডাক্তারকে ফোন")
        assertEquals(
            com.voicedock.ai.command.CommandDecision.Execute,
            CommandPipeline.decide(spec, bengali = true),
        )
        // A reminder is harmless — it must not be treated like a destructive action.
        assertFalse(CommandRegistry.isDestructive(Intent.REMINDER_ADD))
        assertTrue(CommandRegistry.isAvailable(Intent.REMINDER_ADD))
        assertTrue(CommandRegistry.isAvailable(Intent.REMINDER_LIST))
    }

    @Test fun goalCleaningDropsFillersButNotContent() {
        assertEquals("পানি খেতে", ReminderSpeech.goal("৩টায় মনে করিয়ে দাও পানি খেতে"))
        assertTrue(ReminderSpeech.goal("আমাকে কাল সকাল ৯টায় মনে করিয়ে দাও ডাক্তারকে ফোন করতে").contains("ডাক্তারকে"))
    }
}
