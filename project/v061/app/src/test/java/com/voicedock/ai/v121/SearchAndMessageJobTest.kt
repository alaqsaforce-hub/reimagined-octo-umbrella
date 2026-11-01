package com.voicedock.ai.v121

import com.voicedock.ai.jobs.MessageJobCore
import com.voicedock.ai.jobs.SendChannel
import com.voicedock.ai.search.SearchCore
import com.voicedock.ai.search.SearchHit
import com.voicedock.ai.search.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.1 — internet search. Request shape, response parsing and the digest are pure, so every provider is
 * covered without a network call (each payload below is a trimmed real response body).
 */
class SearchCoreTest {

    private val brave = """
        {"web":{"results":[
          {"title":"রাজধানী","url":"https://bn.example.com/dhaka","description":"ঢাকা বাংলাদেশের রাজধানী ও বৃহত্তম শহর।"},
          {"title":"Dhaka - Wikipedia","url":"https://en.example.com/dhaka","description":"Dhaka is the capital of <b>Bangladesh</b> &amp; largest city."},
          {"title":"bad","url":"not-a-url","description":"dropped"}
        ]}}
    """.trimIndent()

    private val tavily = """
        {"results":[{"title":"Dhaka","url":"https://t.example.com/1","content":"Dhaka is the capital city of Bangladesh."}]}
    """.trimIndent()

    private val searx = """
        {"results":[{"title":"ঢাকা","url":"https://s.example.com/1","content":"ঢাকা বাংলাদেশের রাজধানী।","engine":"google"}]}
    """.trimIndent()

    @Test fun braveRequestCarriesTheKeyInItsOwnHeader() {
        val r = SearchCore.request(SearchProvider.BRAVE, "রাজধানী কি", "secret-key", "")!!
        assertTrue(r.url.startsWith("https://api.search.brave.com/res/v1/web/search?q="))
        assertTrue("the query must be URL-encoded", r.url.contains("%E0%A6%B0"))
        assertEquals("secret-key", r.headers["X-Subscription-Token"])
        assertEquals("GET", r.method)
        assertNull("no key, no request", SearchCore.request(SearchProvider.BRAVE, "x", "", ""))
    }

    @Test fun tavilyPostsJsonWithABearerToken() {
        val r = SearchCore.request(SearchProvider.TAVILY, "Dhaka", "tvly-1", "")!!
        assertEquals("POST", r.method)
        assertEquals("Bearer tvly-1", r.headers["Authorization"])
        assertTrue(r.body!!.contains("\"query\":\"Dhaka\""))
        assertTrue(r.body!!.contains("\"max_results\":5"))
        // quotes inside the query must not break the JSON
        assertTrue(SearchCore.request(SearchProvider.TAVILY, "say \"hi\"", "k", "")!!.body!!.contains("\\\"hi\\\""))
    }

    @Test fun searxBaseIsRepairedLikeEveryOtherLocalUrl() {
        assertEquals("http://192.168.0.4:8888", SearchCore.normaliseBase("192.168.0.4:8888"))
        assertEquals("http://192.168.0.4:8888", SearchCore.normaliseBase("http://192.168.0.4:8888/"))
        assertEquals("https://s.example.com", SearchCore.normaliseBase("https://s.example.com/search"))
        assertNull(SearchCore.normaliseBase("  "))
        val r = SearchCore.request(SearchProvider.SEARXNG, "ঢাকা", null, "192.168.0.4:8888")!!
        assertTrue(r.url.contains("format=json"))
        assertNull("no URL, no request", SearchCore.request(SearchProvider.SEARXNG, "x", null, ""))
    }

    @Test fun eachProviderPayloadParsesAndBadRowsAreDropped() {
        val b = SearchCore.parse(SearchProvider.BRAVE, brave)
        assertEquals(2, b.size)
        assertEquals("https://bn.example.com/dhaka", b.first().url)
        // HTML entities and tags are cleaned, not shown raw
        assertTrue(b[1].snippet.contains("Bangladesh & largest"))
        assertFalse(b[1].snippet.contains("<b>"))

        assertEquals(1, SearchCore.parse(SearchProvider.TAVILY, tavily).size)
        assertEquals("ঢাকা", SearchCore.parse(SearchProvider.SEARXNG, searx).first().title)

        // garbage never throws, it returns nothing
        assertEquals(0, SearchCore.parse(SearchProvider.BRAVE, "not json").size)
        assertEquals(0, SearchCore.parse(SearchProvider.SEARXNG, "{}").size)
        assertEquals(0, SearchCore.parse(SearchProvider.NONE, brave).size)
    }

    @Test fun digestAndSourcesAreWhatTheModelAndTheUserSee() {
        val hits = SearchCore.parse(SearchProvider.TAVILY, tavily) + SearchCore.parse(SearchProvider.SEARXNG, searx)
        val digest = SearchCore.digest(hits)
        assertTrue(digest.startsWith("1. "))
        assertTrue(digest.contains("Dhaka is the capital city"))
        val sources = SearchCore.sourcesLine(hits, bengali = true)
        assertTrue(sources.contains("সূত্র"))
        assertTrue(sources.contains("https://t.example.com/1"))
        assertTrue(SearchCore.sourcesLine(emptyList(), true).isEmpty())

        val prompt = SearchCore.prompt("ঢাকা", hits, bengali = true)
        assertTrue(prompt.contains("৩–৪ বাক্যে"))
        assertTrue("the prompt must carry the results, not just the question", prompt.contains("Dhaka is the capital city"))
    }

    @Test fun providerIdsRoundTripAndNoProviderStillHelps() {
        SearchProvider.entries.forEach { assertEquals(it, SearchProvider.byId(it.id)) }
        assertEquals(SearchProvider.NONE, SearchProvider.byId("nonsense"))
        assertEquals(SearchProvider.NONE, SearchProvider.byId(null))
        val hint = SearchCore.browserHint("আজকের আবহাওয়া", bengali = true)
        assertTrue("it must say what is missing", hint.contains("সার্চ সার্ভার"))
        assertTrue("and still give a working link", hint.contains("duckduckgo.com"))
        assertTrue(SearchCore.browserUrl("a b").endsWith("a+b"))
    }
}

/**
 * v1.2.1 — "রাতে রহিমকে বলো আমি কাল আসব". The rule under test is the promise: a message is only ever called
 * "sent" when it really was sent without the user.
 */
class MessageJobTest {

    @Test fun aLiveReplyableNotificationIsTheOnlySilentPath() {
        val silent = MessageJobCore.plan(liveReplyable = true, canOpenChat = true)
        assertEquals(SendChannel.DIRECT_REPLY, silent.channel)
        assertTrue(silent.sendsWithoutUser)

        val chat = MessageJobCore.plan(liveReplyable = false, canOpenChat = true)
        assertEquals(SendChannel.OPEN_CHAT, chat.channel)
        assertFalse(chat.sendsWithoutUser)

        val blocked = MessageJobCore.plan(liveReplyable = false, canOpenChat = false)
        assertEquals(SendChannel.BLOCKED, blocked.channel)
        assertFalse(blocked.sendsWithoutUser)

        listOf(silent, chat, blocked).forEach {
            assertTrue(it.noteBn.any { c -> c in '\u0980'..'\u09FF' })
            assertTrue(it.noteEn.isNotBlank())
        }
    }

    @Test fun theFiredTextNeverClaimsMoreThanHappened() {
        val sent = MessageJobCore.firedText("রহিম", "আমি কাল আসব", SendChannel.DIRECT_REPLY, bengali = true)
        assertTrue(sent.contains("পাঠানো হয়েছে"))
        val open = MessageJobCore.firedText("রহিম", "আমি কাল আসব", SendChannel.OPEN_CHAT, bengali = true)
        assertTrue("must ask the user to press send", open.contains("পাঠাও"))
        val failed = MessageJobCore.firedText("রহিম", "আমি কাল আসব", SendChannel.BLOCKED, bengali = true)
        assertTrue(failed.contains("পাঠানো যায়নি"))
    }

    @Test fun whatsappLinkHasOnlyDigitsAndEncodedText() {
        val url = MessageJobCore.whatsappUrl("+880 1712-345678", "আমি কাল আসব, ঠিক আছে?")
        assertTrue(url.startsWith("https://wa.me/8801712345678?text="))
        assertFalse("no spaces survive encoding", url.contains(" "))
        assertTrue(url.contains("%E0%A6%86"))
    }

    @Test fun aMessageJobWithoutTargetOrBodyIsNotFired() {
        assertTrue(MessageJobCore.canFire(MessageJobCore.KIND_MESSAGE, "রহিম", "হ্যালো"))
        assertFalse(MessageJobCore.canFire(MessageJobCore.KIND_MESSAGE, "", "হ্যালো"))
        assertFalse(MessageJobCore.canFire(MessageJobCore.KIND_MESSAGE, "রহিম", "  "))
        assertFalse("a reminder is not a message", MessageJobCore.canFire(MessageJobCore.KIND_REMINDER, "রহিম", "হ্যালো"))
    }

    @Test fun theScheduleConfirmationStatesTheDeliveryPath() {
        val plan = MessageJobCore.plan(liveReplyable = false, canOpenChat = true)
        val text = MessageJobCore.scheduledText("রহিম", "আমি কাল আসব", "আজ সন্ধ্যা ৭:০০", plan, bengali = true)
        assertTrue(text.contains("আজ সন্ধ্যা ৭:০০"))
        assertTrue(text.contains("রহিম"))
        assertTrue("the limit is part of the promise", text.contains("‘পাঠাও’"))
    }
}
