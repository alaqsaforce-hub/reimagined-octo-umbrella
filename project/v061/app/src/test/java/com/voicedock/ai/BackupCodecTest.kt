package com.voicedock.ai

import com.voicedock.ai.backup.BackupCodec
import com.voicedock.ai.backup.BackupDecode
import com.voicedock.ai.backup.BackupDocument
import com.voicedock.ai.backup.BackupDraft
import com.voicedock.ai.backup.BackupFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.5 — the backup format. Pure JVM: no Android, no Robolectric.
 *
 * The most important test in this file is [encodedFileCannotContainSecrets]: the backup model has no
 * field for credentials, and this test fails if somebody ever adds one.
 */
class BackupCodecTest {

    private fun draft(id: String = "d1", text: String = "আজকের কাজ") = BackupDraft(
        id = id, createdAt = 1, updatedAt = 2, rawText = text, formattedText = text,
        status = "SAVED", language = "bn-BD", engine = "AUTO",
    )

    private fun document(title: String = "নোট", chunks: List<String> = listOf("প্রথম অংশ", "দ্বিতীয় অংশ")) =
        BackupDocument(title = title, chunks = chunks, contentHash = BackupCodec.hashOfChunks(chunks))

    private fun ok(json: String): BackupDecode.Ok {
        val d = BackupCodec.decode(json)
        assertTrue("expected Ok but got $d", d is BackupDecode.Ok)
        return d as BackupDecode.Ok
    }

    private fun error(json: String): String {
        val d = BackupCodec.decode(json)
        assertTrue("expected Error but got $d", d is BackupDecode.Error)
        return (d as BackupDecode.Error).reason
    }

    // ---- round trip ----

    @Test
    fun roundTripKeepsDraftsAndDocuments() {
        val file = BackupFile(
            appVersion = "0.6.5", exportedAt = 123,
            drafts = listOf(draft("a", "প্রথম"), draft("b", "দ্বিতীয়")),
            documents = listOf(document()),
        )
        val back = ok(BackupCodec.encode(file)).file
        assertEquals(2, back.drafts.size)
        assertEquals("প্রথম", back.drafts.first().rawText)
        assertEquals(1, back.documents.size)
        assertEquals(listOf("প্রথম অংশ", "দ্বিতীয় অংশ"), back.documents.first().chunks)
        assertEquals(123L, back.exportedAt)
    }

    @Test
    fun encodedFileCannotContainSecrets() {
        val json = BackupCodec.encode(
            BackupFile(drafts = listOf(draft()), documents = listOf(document()))
        ).lowercase()
        listOf(
            "apikey", "api_key", "accesstoken", "access_token", "refreshtoken", "idtoken",
            "clientsecret", "password", "bearer", "authorization", "cookie", "otp", "pin",
            "secret", "keystore",
        ).forEach { forbidden ->
            assertFalse("backup JSON must never contain a '$forbidden' field", json.contains("\"$forbidden\""))
        }
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() {
        val json = """
            {"app":"com.voicedock.ai","schema":1,"appVersion":"9.9.9","exportedAt":5,
             "drafts":[{"id":"x","rawText":"hi","formattedText":"hi","futureField":true}],
             "documents":[],"somethingNew":{"a":1}}
        """.trimIndent()
        val file = ok(json).file
        assertEquals(1, file.drafts.size)
        assertEquals("hi", file.drafts.first().rawText)
    }

    // ---- rejection ----

    @Test
    fun emptyInputIsRejected() {
        assertTrue(error("").isNotBlank())
        assertTrue(error("   ").isNotBlank())
    }

    @Test
    fun nonJsonIsRejected() {
        assertTrue(error("this is not json").contains("not a VoiceDock backup", ignoreCase = true))
    }

    @Test
    fun foreignAppIsRejected() {
        val json = """{"app":"com.evil.app","schema":1,"drafts":[],"documents":[]}"""
        assertTrue(error(json).contains("another app"))
    }

    @Test
    fun newerSchemaIsRejectedInsteadOfHalfImported() {
        val json = """{"app":"com.voicedock.ai","schema":99,"drafts":[],"documents":[]}"""
        assertTrue(error(json).contains("Unsupported backup version"))
    }

    @Test
    fun olderThanSupportedSchemaIsRejected() {
        val json = """{"app":"com.voicedock.ai","schema":0,"drafts":[],"documents":[]}"""
        assertTrue(error(json).contains("Unsupported backup version"))
    }

    // ---- draft sanitising ----

    @Test
    fun blankDraftsAreDropped() {
        val json = """
            {"app":"com.voicedock.ai","schema":1,"exportedAt":1,
             "drafts":[{"id":"a","rawText":"","formattedText":""},{"id":"b","rawText":"ok","formattedText":""}],
             "documents":[]}
        """.trimIndent()
        val d = ok(json)
        assertEquals(1, d.file.drafts.size)
        assertEquals("b", d.file.drafts.first().id)
        assertTrue(d.notes.any { it.contains("skipped") })
    }

    @Test
    fun duplicateDraftIdsAreDropped() {
        val json = """
            {"app":"com.voicedock.ai","schema":1,
             "drafts":[{"id":"same","rawText":"one","formattedText":"one"},
                       {"id":"same","rawText":"two","formattedText":"two"}],
             "documents":[]}
        """.trimIndent()
        val d = ok(json)
        assertEquals(1, d.file.drafts.size)
        assertEquals("one", d.file.drafts.first().rawText)
    }

    @Test
    fun draftWithoutIdIsDropped() {
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[{"id":"","rawText":"x","formattedText":"x"}],"documents":[]}"""
        assertEquals(0, ok(json).file.drafts.size)
    }

    @Test
    fun draftStatusIsSanitised() {
        assertEquals("REVIEW", BackupCodec.sanitizeStatus("review"))
        assertEquals("SAVED", BackupCodec.sanitizeStatus("!!junk!!"))
        assertEquals("SAVED", BackupCodec.sanitizeStatus(""))
        assertEquals("CLEARED", BackupCodec.sanitizeStatus(" ClearEd "))
    }

    @Test
    fun draftTextIsBounded() {
        val huge = "ক".repeat(BackupCodec.MAX_TEXT_PER_FIELD + 5_000)
        val json = BackupCodec.encode(BackupFile(drafts = listOf(BackupDraft(id = "a", rawText = huge, formattedText = huge))))
        val file = ok(json).file
        assertEquals(BackupCodec.MAX_TEXT_PER_FIELD, file.drafts.first().rawText.length)
    }

    @Test
    fun tooManyDraftsAreCapped() {
        val many = (0 until BackupCodec.MAX_DRAFTS + 3).map { BackupDraft(id = "id$it", rawText = "t", formattedText = "t") }
        val json = BackupCodec.encode(BackupFile(drafts = many))
        val d = ok(json)
        assertEquals(BackupCodec.MAX_DRAFTS, d.file.drafts.size)
        assertTrue(d.notes.any { it.contains("first") })
    }

    // ---- documents ----

    @Test
    fun documentWithoutChunksIsDropped() {
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[],"documents":[{"title":"t","chunks":["","   "]}]}"""
        assertEquals(0, ok(json).file.documents.size)
    }

    @Test
    fun blankTitleFallsBackToTheFirstChunk() {
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[],"documents":[{"title":"  ","chunks":["আমার প্রথম নোট"]}]}"""
        assertEquals("আমার প্রথম নোট", ok(json).file.documents.first().title)
    }

    @Test
    fun missingHashIsRecomputedDeterministically() {
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[],"documents":[{"title":"t","chunks":["abc"]}]}"""
        val first = ok(json).file.documents.first().contentHash
        val second = ok(json).file.documents.first().contentHash
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    @Test
    fun validHashIsPreserved() {
        val hash = BackupCodec.hashOfChunks(listOf("abc"))
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[],"documents":[{"title":"t","chunks":["abc"],"contentHash":"$hash"}]}"""
        assertEquals(hash, ok(json).file.documents.first().contentHash)
        assertNotEquals("", hash)
    }

    @Test
    fun duplicateDocumentsByHashAreCollapsed() {
        val hash = BackupCodec.hashOfChunks(listOf("same text"))
        val json = """
            {"app":"com.voicedock.ai","schema":1,"drafts":[],
             "documents":[{"title":"one","chunks":["same text"],"contentHash":"$hash"},
                          {"title":"two","chunks":["same text"],"contentHash":"$hash"}]}
        """.trimIndent()
        val d = ok(json)
        assertEquals(1, d.file.documents.size)
        assertEquals("one", d.file.documents.first().title)
    }

    @Test
    fun unknownSourceFallsBackToNote() {
        val json = """{"app":"com.voicedock.ai","schema":1,"drafts":[],"documents":[{"title":"t","source":"WHATEVER","chunks":["x"]}]}"""
        assertEquals("NOTE", ok(json).file.documents.first().source)
        assertEquals("GMAIL", BackupCodec.sanitizeSource("gmail"))
    }

    @Test
    fun totalCharsCountsEverything() {
        val file = BackupFile(
            drafts = listOf(BackupDraft(id = "a", rawText = "12345", formattedText = "12")),
            documents = listOf(BackupDocument(title = "t", chunks = listOf("1234", "123"))),
        )
        assertEquals(5 + 2 + 4 + 3, file.totalChars)
    }

    @Test
    fun anEmptyBackupIsValidAndHarmless() {
        val d = ok(BackupCodec.encode(BackupFile()))
        assertTrue(d.file.drafts.isEmpty())
        assertTrue(d.file.documents.isEmpty())
        assertTrue(d.notes.isEmpty())
    }
}
