package com.voicedock.ai

import com.voicedock.ai.context.ContextKind
import com.voicedock.ai.context.ContextPolicy
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.context.DefaultSafetyOracle
import com.voicedock.ai.context.PolicyConfig
import com.voicedock.ai.context.ProbePolicy
import com.voicedock.ai.context.BlockReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.4 — the user-maintained probe blocklist and its interaction with the privacy gate.
 * Pure JVM: no Android, no Robolectric.
 */
class ProbePolicyTest {

    // ---- normalize ----

    @Test
    fun normalizeAcceptsRealPackageNames() {
        assertEquals("com.whatsapp", ProbePolicy.normalize("com.whatsapp"))
        assertEquals("com.whatsapp", ProbePolicy.normalize("  COM.WhatsApp  "))
        assertEquals("org.bkash.bd_wallet2", ProbePolicy.normalize("org.bkash.bd_wallet2"))
    }

    @Test
    fun normalizeRejectsJunk() {
        assertNull(ProbePolicy.normalize(""))
        assertNull(ProbePolicy.normalize("   "))
        assertNull(ProbePolicy.normalize("whatsapp"))          // no dot
        assertNull(ProbePolicy.normalize("com..whatsapp"))      // empty segment
        assertNull(ProbePolicy.normalize("com.whatsapp."))      // trailing dot
        assertNull(ProbePolicy.normalize("com.whatsapp*"))      // no wildcards
        assertNull(ProbePolicy.normalize("com.whats app"))      // space
        assertNull(ProbePolicy.normalize("com.9gag"))           // segment must start with a letter
        assertNull(ProbePolicy.normalize("a".repeat(200) + ".com"))
    }

    // ---- add / remove / sanitize ----

    @Test
    fun addIsIdempotentAndCaseInsensitive() {
        val once = ProbePolicy.add(emptyList(), "com.whatsapp")
        val twice = ProbePolicy.add(once, "COM.WHATSAPP")
        assertEquals(listOf("com.whatsapp"), twice)
    }

    @Test
    fun addIgnoresInvalidInput() {
        assertEquals(emptyList<String>(), ProbePolicy.add(emptyList(), "not a package"))
    }

    @Test
    fun addRespectsTheCap() {
        var list = emptyList<String>()
        repeat(ProbePolicy.MAX_PACKAGES + 5) { i -> list = ProbePolicy.add(list, "com.example.app$i") }
        assertEquals(ProbePolicy.MAX_PACKAGES, list.size)
    }

    @Test
    fun removeIsCaseInsensitiveAndIgnoresUnknown() {
        val list = listOf("com.whatsapp", "com.facebook.katana")
        assertEquals(listOf("com.facebook.katana"), ProbePolicy.remove(list, "COM.WHATSAPP"))
        assertEquals(list, ProbePolicy.remove(list, "com.absent"))
    }

    @Test
    fun sanitizeDropsJunkAndDuplicatesPreservingOrder() {
        val out = ProbePolicy.sanitize(listOf("com.b", " junk ", "COM.B", "com.a", ""))
        assertEquals(listOf("com.b", "com.a"), out)
    }

    // ---- matching ----

    @Test
    fun matchesExactPackageCaseInsensitively() {
        assertTrue(ProbePolicy.matches("com.whatsapp", listOf("com.whatsapp")))
        assertTrue(ProbePolicy.matches("com.whatsapp", listOf("COM.WHATSAPP")))
        assertFalse(ProbePolicy.matches("com.telegram", listOf("com.whatsapp")))
    }

    @Test
    fun matchesChildPackagesButNotSiblingsWithSharedPrefix() {
        val blocked = listOf("com.example.bank")
        assertTrue(ProbePolicy.matches("com.example.bank.wallet", blocked))
        assertTrue(ProbePolicy.matches("com.example.bank.wallet.ui", blocked))
        // prefix sharing must not leak: com.example.bankrupt is a different app
        assertFalse(ProbePolicy.matches("com.example.bankrupt", blocked))
    }

    @Test
    fun matchesEmptyInputsAreSafe() {
        assertFalse(ProbePolicy.matches("", listOf("com.whatsapp")))
        assertFalse(ProbePolicy.matches("com.whatsapp", emptyList()))
    }

    // ---- pre-traversal gate ----

    @Test
    fun userBlocklistBlocksAnyAppEvenWithoutFinanceRules() {
        assertTrue(ProbePolicy.isBlocked("com.whatsapp", "WhatsApp",
            listOf("com.whatsapp"), DefaultSafetyOracle))
        assertTrue(ProbePolicy.userBlockedOnly("com.whatsapp", "WhatsApp",
            listOf("com.whatsapp"), DefaultSafetyOracle))
    }

    @Test
    fun builtInFinanceRulesKeepWorkingWhenTheUserListIsEmpty() {
        assertTrue(ProbePolicy.isBlocked("com.bkash.customerapp", "bKash", emptyList(), DefaultSafetyOracle))
        // Finance packages are NOT reported as "user blocked": the bubble must blame the right rule.
        assertFalse(ProbePolicy.userBlockedOnly("com.bkash.customerapp", "bKash", emptyList(), DefaultSafetyOracle))
    }

    @Test
    fun ordinaryAppIsNotBlocked() {
        assertFalse(ProbePolicy.isBlocked("com.whatsapp", "WhatsApp", emptyList(), DefaultSafetyOracle))
    }

    // ---- privacy gate contract ----

    @Test
    fun userBlockedScreenIsSecureWithItsOwnReason() {
        val snapshot = ContextSnapshot(pkg = "com.whatsapp", appLabel = "WhatsApp", probeOk = true, userBlocked = true)
        val (kind, reason) = ContextPolicy.evaluate(snapshot, PolicyConfig(screenContextConsent = true))
        assertEquals(ContextKind.SECURE, kind)
        assertEquals(BlockReason.USER_BLOCKED, reason)
    }

    @Test
    fun policyConfigBlocklistIsAlsoHonoured() {
        val snapshot = ContextSnapshot(pkg = "com.whatsapp", appLabel = "WhatsApp", probeOk = true)
        val (kind, reason) = ContextPolicy.evaluate(
            snapshot,
            PolicyConfig(screenContextConsent = true, extraBlockedPackages = setOf("com.whatsapp")),
        )
        assertEquals(ContextKind.SECURE, kind)
        assertEquals(BlockReason.USER_BLOCKED, reason)
    }

    @Test
    fun consentStillWinsOverEverything() {
        val snapshot = ContextSnapshot(pkg = "com.whatsapp", userBlocked = true)
        val (kind, reason) = ContextPolicy.evaluate(snapshot, PolicyConfig(screenContextConsent = false))
        assertEquals(ContextKind.SECURE, kind)
        assertEquals(BlockReason.CONSENT_OFF, reason)
    }

    @Test
    fun userBlockedReasonHasAnExplanationInBothLanguages() {
        assertTrue(ContextPolicy.blockExplanation(BlockReason.USER_BLOCKED, true).isNotBlank())
        assertTrue(ContextPolicy.blockExplanation(BlockReason.USER_BLOCKED, false).isNotBlank())
    }

    @Test
    fun unblockedOrdinaryScreenStillClassifiesAsFieldFound() {
        val snapshot = ContextSnapshot(
            pkg = "com.whatsapp",
            appLabel = "WhatsApp",
            probeOk = true,
            fields = listOf(com.voicedock.ai.context.FieldInfo(focused = true, editable = true)),
        )
        val (kind, _) = ContextPolicy.evaluate(snapshot, PolicyConfig(screenContextConsent = true))
        assertEquals(ContextKind.FIELD_FOUND, kind)
    }
}
