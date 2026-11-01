package com.voicedock.ai.v120

import com.voicedock.ai.ai.AiCandidate
import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.AiRouter
import com.voicedock.ai.ai.ChatClient
import com.voicedock.ai.ai.ChatMessage
import com.voicedock.ai.ai.LocalServer
import com.voicedock.ai.ai.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 — "a local model must not fail for a reason the app could have fixed".
 *
 * Covers the URL/path normalisation, the model ranking, the retry rules and the router's use of them.
 */
class LocalServerAndRetryTest {

    // ---- address normalisation --------------------------------------------------------------

    @Test fun bareHostPortGetsSchemeAndVersion() {
        assertEquals("http://192.168.0.5:11434/v1", LocalServer.normalise("192.168.0.5:11434").url)
        assertEquals("http://localhost:11434/v1", LocalServer.normalise("localhost:11434").url)
        assertEquals("http://192.168.0.5:11434/v1", LocalServer.normalise("http://192.168.0.5:11434/").url)
    }

    @Test fun pastedEndpointPathsAreTrimmed() {
        assertEquals("http://192.168.0.5:11434/v1", LocalServer.normalise("http://192.168.0.5:11434/api/tags").url)
        assertEquals("http://192.168.0.5:11434/v1", LocalServer.normalise("http://192.168.0.5:11434/v1/chat/completions").url)
        assertEquals("http://192.168.0.5:1234/v1", LocalServer.normalise("http://192.168.0.5:1234/v1/models").url)
    }

    @Test fun alreadyCorrectUrlIsUntouched() {
        val p = LocalServer.normalise("http://192.168.0.10:11434/v1")
        assertEquals("http://192.168.0.10:11434/v1", p.url)
        assertFalse(p.changed)
        // A custom base path (a reverse proxy in front of Ollama) is respected, not rewritten.
        assertEquals("https://ai.example.com/ollama/v1", LocalServer.normalise("https://ai.example.com/ollama/v1").url)
    }

    @Test fun unsafeOrInvalidInputIsNotRepaired() {
        // userinfo / query / fragment are policy problems: the normaliser must not launder them.
        assertEquals("http://127.0.0.1:8080@attacker.example/v1", LocalServer.normalise("http://127.0.0.1:8080@attacker.example/v1").url)
        assertEquals("http://host/v1?x=1", LocalServer.normalise("http://host/v1?x=1").url)
        assertEquals("", LocalServer.normalise("   ").url)
    }

    @Test fun serverKindIsDetectedFromPort() {
        assertEquals(LocalServer.Kind.OLLAMA, LocalServer.kindOf("http://192.168.0.5:11434/v1"))
        assertEquals(LocalServer.Kind.LM_STUDIO, LocalServer.kindOf("http://192.168.0.5:1234/v1"))
        assertEquals(LocalServer.Kind.LLAMA_CPP, LocalServer.kindOf("http://192.168.0.5:8080/v1"))
        assertEquals(LocalServer.Kind.OTHER, LocalServer.kindOf("http://192.168.0.5:9999/v1"))
        // An address that needs normalising still reports the right server.
        assertEquals(LocalServer.Kind.OLLAMA, LocalServer.normalise("192.168.0.5:11434").kind)
    }

    @Test fun chatModelsOutrankEmbeddingModels() {
        val models = listOf("nomic-embed-text:latest", "llava:latest", "qwen2.5:1.5b", "gemma2:2b-instruct")
        val ranked = LocalServer.rank(models)
        assertTrue("an instruct chat model must come first", ranked.first().contains("instruct") || ranked.first() == "qwen2.5:1.5b")
        assertTrue("embedding models must sink", ranked.indexOf("nomic-embed-text:latest") >= 2)
    }

    @Test fun everyDiagnosisIsAWarningWithAnAction() {
        for (kind in LocalServer.Kind.entries) {
            for (code in listOf("unreachable", "timeout", "not_found", "auth", "no_models")) {
                val bn = LocalServer.diagnose(kind, code, bengali = true)
                assertTrue("bengali text for $code", bn.any { it in '\u0980'..'\u09FF' })
                assertTrue("english text for $code", LocalServer.diagnose(kind, code, bengali = false).isNotBlank())
            }
        }
        assertTrue(LocalServer.diagnose(LocalServer.Kind.OLLAMA, "no_models", true).contains("ollama pull"))
    }

    // ---- retry rules ------------------------------------------------------------------------

    @Test fun transientFailuresGetOneRetryAndPermanentOnesGetNone() {
        assertEquals(1, RetryPolicy.retries(AiException.Kind.NETWORK))
        assertEquals(1, RetryPolicy.retries(AiException.Kind.TIMEOUT))
        assertEquals(1, RetryPolicy.retries(AiException.Kind.SERVER, 502))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.AUTH, 401))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.BAD_REQUEST, 400))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.BLOCKED))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.EMPTY))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.NOT_CONFIGURED))
    }

    @Test fun shortRateLimitsAreWaitedOutButLongOnesAreNot() {
        assertEquals(1, RetryPolicy.retries(AiException.Kind.RATE_LIMIT, 429, retryAfterSeconds = 2))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.RATE_LIMIT, 429, retryAfterSeconds = 60))
        assertEquals(0, RetryPolicy.retries(AiException.Kind.RATE_LIMIT, 429, retryAfterSeconds = null))
        assertEquals(2_000L, RetryPolicy.delayMs(AiException.Kind.RATE_LIMIT, 0, retryAfterSeconds = 2))
        assertEquals(400L, RetryPolicy.delayMs(AiException.Kind.NETWORK, 0))
        assertEquals(1_200L, RetryPolicy.delayMs(AiException.Kind.NETWORK, 1))
    }

    // ---- the router actually uses them ------------------------------------------------------

    private class FlakyClient(private val failures: Int, private val kind: AiException.Kind) : ChatClient {
        var calls = 0
        override fun listModels(): List<String> = listOf("m")
        override fun complete(model: String, system: String, messages: List<ChatMessage>): String {
            calls++
            if (calls <= failures) throw AiException(kind, 0, "boom", AiProvider.CUSTOM)
            return "ok after $calls"
        }
    }

    @Test fun routerRetriesTheSameProviderBeforeGivingUp() {
        val client = FlakyClient(failures = 1, kind = AiException.Kind.NETWORK)
        val slept = mutableListOf<Long>()
        val router = AiRouter(candidates = { listOf(AiCandidate(AiProvider.CUSTOM, "qwen2.5:1.5b", client)) }, sleep = { slept += it })
        val answer = router.complete("sys", listOf(ChatMessage.user("hi")))
        assertEquals("ok after 2", answer.text)
        assertEquals(2, client.calls)
        assertEquals(listOf(400L), slept)
    }

    @Test fun routerDoesNotRetryAnAuthFailure() {
        val client = FlakyClient(failures = 5, kind = AiException.Kind.AUTH)
        val router = AiRouter(candidates = { listOf(AiCandidate(AiProvider.CUSTOM, "m", client)) }, sleep = { })
        val e = runCatching { router.complete("s", listOf(ChatMessage.user("hi"))) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is AiException)
        assertEquals(1, client.calls)
        assertTrue((e as AiException).message!!.contains("CUSTOM") || e.message!!.contains("Custom"))
    }
}
