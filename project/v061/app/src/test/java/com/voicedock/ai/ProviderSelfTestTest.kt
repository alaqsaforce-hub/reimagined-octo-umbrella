package com.voicedock.ai

import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.ProviderSelfTestCore
import com.voicedock.ai.ai.ProviderTestResult
import com.voicedock.ai.ai.TestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.0 — "are the providers that are already signed in actually working?"
 *
 * The network half runs on the phone; the decision half — what to call each provider's state, and how to
 * summarise it without over-claiming — is pure and tested here. The rule: a provider is only "working" if
 * a real authenticated call succeeded, never because a key happens to exist.
 */
class ProviderSelfTestTest {

    private fun r(p: AiProvider, s: TestStatus, d: String = "") = ProviderTestResult(p, s, d)

    @Test fun aConfiguredAndAllowedProviderIsTestable() {
        assertEquals(TestStatus.OK, ProviderSelfTestCore.status(blocked = null, hasCredentials = true))
    }

    @Test fun aMissingCredentialIsNotAFailure() {
        assertEquals(TestStatus.NO_CREDENTIALS, ProviderSelfTestCore.status(blocked = null, hasCredentials = false))
        assertEquals(TestStatus.NO_CREDENTIALS, ProviderSelfTestCore.status("not_configured", hasCredentials = false))
        assertEquals(TestStatus.NO_CREDENTIALS, ProviderSelfTestCore.status("no_key", hasCredentials = false))
    }

    @Test fun consentAndPolicyBlocksAreReportedAsBlocked() {
        // e.g. local-only mode with a cloud key, or a paid provider while "allow paid" is off
        listOf("local_only", "paid_disabled", "no_cloud_consent", "insecure_url", "custom_url_missing").forEach {
            assertEquals(it, TestStatus.BLOCKED, ProviderSelfTestCore.status(it, hasCredentials = true))
        }
    }

    @Test fun theSummaryNeverCountsAnUntestedProviderAsWorking() {
        val results = listOf(
            r(AiProvider.GEMINI, TestStatus.OK, "18 models"),
            r(AiProvider.CHATGPT, TestStatus.FAILED, "401 unauthorized"),
            r(AiProvider.GROQ, TestStatus.NO_CREDENTIALS),
            r(AiProvider.OPENROUTER, TestStatus.BLOCKED, "paid only"),
        )
        val text = ProviderSelfTestCore.summarize(results, bengali = false)
        assertTrue(text.contains("1 working"))
        assertTrue(text.contains("1 failed"))
        assertTrue(text.contains("1 blocked"))
        assertTrue(text.contains("1 not configured"))

        val marks = ProviderSelfTestCore.mark(results)
        assertEquals(1, marks[TestStatus.OK])
        assertEquals(1, marks[TestStatus.FAILED])
    }

    @Test fun nothingTestedIsSaidPlainly() {
        val bn = ProviderSelfTestCore.summarize(emptyList(), bengali = true)
        assertTrue(bn.contains("পরীক্ষা"))
        val en = ProviderSelfTestCore.summarize(emptyList(), bengali = false)
        assertTrue(en.contains("Nothing tested"))
    }

    @Test fun eachRowCarriesAVisibleMark() {
        assertTrue(ProviderSelfTestCore.detailFor(TestStatus.OK, "12 models", false).startsWith("✓"))
        assertTrue(ProviderSelfTestCore.detailFor(TestStatus.FAILED, "network", false).startsWith("✗"))
        assertTrue(ProviderSelfTestCore.detailFor(TestStatus.BLOCKED, "paid only", false).startsWith("⛔"))
        assertTrue(ProviderSelfTestCore.detailFor(TestStatus.NO_CREDENTIALS, "", false).startsWith("○"))
        // the detail text is preserved so the user can act on it
        assertTrue(ProviderSelfTestCore.detailFor(TestStatus.FAILED, "401 unauthorized", false).contains("401"))
    }

    @Test fun theChatGptStyleAccountProvidersAreCovered() {
        // The user asked specifically about sign-in based providers: both must be testable entries.
        val ids = AiProvider.entries.map { it.id }
        assertTrue(ids.contains("chatgpt"))
        assertTrue(ids.contains("gemini"))
        assertTrue(ids.contains("openrouter"))
        assertTrue(ids.contains("custom"))
    }
}
