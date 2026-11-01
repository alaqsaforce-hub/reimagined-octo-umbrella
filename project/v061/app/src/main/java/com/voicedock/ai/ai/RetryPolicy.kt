package com.voicedock.ai.ai

/**
 * v1.2.0 — when a provider failure deserves a second try, and when it deserves the next provider instead.
 *
 * Before this, the very first hiccup (a Wi-Fi handoff, one 502, one slow local model) burned the provider
 * and, with a single provider configured, looked like "AI is broken". The rules are pure and unit-tested;
 * [AiRouter] is the only caller.
 */
object RetryPolicy {

    const val MAX_RETRIES = 1

    /** How many extra attempts the SAME provider gets before the router moves on. */
    fun retries(kind: AiException.Kind, httpCode: Int = 0, retryAfterSeconds: Int? = null): Int = when (kind) {
        // transient by nature
        AiException.Kind.NETWORK, AiException.Kind.TIMEOUT, AiException.Kind.SERVER -> MAX_RETRIES
        // a short rate limit is worth waiting out, a long one is not
        AiException.Kind.RATE_LIMIT -> if (retryAfterSeconds != null && retryAfterSeconds in 1..3) 1 else 0
        // never helps: the credentials, the request or the content are the problem
        AiException.Kind.AUTH, AiException.Kind.BAD_REQUEST, AiException.Kind.BLOCKED,
        AiException.Kind.EMPTY, AiException.Kind.NOT_CONFIGURED -> 0
    }

    fun isRetryable(kind: AiException.Kind, httpCode: Int = 0, retryAfterSeconds: Int? = null): Boolean =
        retries(kind, httpCode, retryAfterSeconds) > 0

    /** Backoff in ms for retry number [attempt] (0-based). Server-provided `Retry-After` wins when sane. */
    fun delayMs(kind: AiException.Kind, attempt: Int, retryAfterSeconds: Int? = null): Long {
        if (retryAfterSeconds != null && retryAfterSeconds in 1..3) return retryAfterSeconds * 1000L
        return when (kind) {
            AiException.Kind.NETWORK, AiException.Kind.TIMEOUT -> if (attempt <= 0) 400L else 1200L
            AiException.Kind.SERVER -> 600L * (attempt + 1)
            AiException.Kind.RATE_LIMIT -> 1000L
            else -> 0L
        }
    }
}
