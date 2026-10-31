package com.voicedock.ai

import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.AiReadinessCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.6.7 — the bubble must explain *why* the AI is unusable instead of always saying "add a key". */
class AiReadinessTest {

    private val order = listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENAI)

    private fun decide(
        reasons: Map<AiProvider, String?>,
        keys: Set<AiProvider> = emptySet(),
    ) = AiReadinessCore.decide(
        order = order,
        reason = { reasons[it] },
        hasKey = { it in keys },
    )

    @Test
    fun `first usable provider in order wins`() {
        val d = decide(mapOf(AiProvider.GEMINI to null, AiProvider.GROQ to null, AiProvider.OPENAI to null))
        assertTrue(d.ok)
        assertEquals(AiProvider.GEMINI, d.provider)
        assertEquals(null, d.code)
    }

    @Test
    fun `a blocked provider before a usable one is skipped`() {
        val d = decide(mapOf(AiProvider.GEMINI to "no_key", AiProvider.GROQ to null, AiProvider.OPENAI to null))
        assertTrue(d.ok)
        assertEquals(AiProvider.GROQ, d.provider)
    }

    @Test
    fun `no usable provider explains the one that holds a key`() {
        val d = decide(
            mapOf(AiProvider.GEMINI to "no_model", AiProvider.GROQ to "no_key", AiProvider.OPENAI to "paid_off"),
            keys = setOf(AiProvider.GEMINI),
        )
        assertFalse(d.ok)
        assertEquals(AiProvider.GEMINI, d.provider)
        assertEquals("no_model", d.code)
    }

    @Test
    fun `with no key anywhere the reason is no_key`() {
        val d = decide(mapOf(AiProvider.GEMINI to "no_key", AiProvider.GROQ to "no_key", AiProvider.OPENAI to "no_key"))
        assertFalse(d.ok)
        assertEquals("no_key", d.code)
        assertEquals(AiProvider.GEMINI, d.provider)
    }

    @Test
    fun `a stuck custom endpoint is not blamed when no key exists`() {
        val d = AiReadinessCore.decide(
            order = listOf(AiProvider.CUSTOM, AiProvider.GEMINI),
            reason = { p -> if (p == AiProvider.CUSTOM) "no_url" else "no_key" },
            hasKey = { false },
        )
        assertEquals(AiProvider.GEMINI, d.provider)
        assertEquals("no_key", d.code)
    }

    @Test
    fun `missing model does not say add a key`() {
        val bn = AiReadinessCore.message("no_model", "Google Gemini", bengali = true)
        val en = AiReadinessCore.message("no_model", "Google Gemini", bengali = false)
        assertTrue(en.contains("Test"))
        assertTrue(en.contains("Gemini"))
        assertFalse(en.contains("No key", ignoreCase = true))
        assertNotEquals(bn, en)
        // Bengali wording must also avoid the "key" claim.
        assertFalse(bn.contains("key নেই"))
    }

    @Test
    fun `consent and local-only get their own sentences`() {
        val consent = AiReadinessCore.message("no_consent", "Groq", bengali = false)
        val local = AiReadinessCore.message("local_only", "Groq", bengali = false)
        assertTrue(consent.contains("consent", ignoreCase = true))
        assertTrue(local.contains("Local-only", ignoreCase = true))
        assertNotEquals(consent, local)
    }

    @Test
    fun `paid provider explains both ways out`() {
        val m = AiReadinessCore.message("paid_off", "OpenAI", bengali = false)
        assertTrue(m.contains("paid", ignoreCase = true))
        assertTrue(m.contains("free key", ignoreCase = true))
    }

    @Test
    fun `every block code has a readable message and asks for settings`() {
        val codes = listOf("no_key", "no_model", "no_consent", "local_only", "paid_off", "no_url", "invalid_url", "insecure_url")
        for (c in codes) {
            val bn = AiReadinessCore.message(c, "AI", bengali = true)
            val en = AiReadinessCore.message(c, "AI", bengali = false)
            assertTrue("empty message for $c", bn.isNotBlank() && en.isNotBlank())
            assertTrue("raw code leaked for $c", !en.startsWith(c))
            assertTrue("no settings hint for $c", AiReadinessCore.needsSettings(c))
        }
        assertFalse(AiReadinessCore.needsSettings(null))
    }

    @Test
    fun `an empty provider order never crashes`() {
        val d = AiReadinessCore.decide(emptyList(), { "no_key" }, { false })
        assertFalse(d.ok)
        assertEquals("no_key", d.code)
    }
}
