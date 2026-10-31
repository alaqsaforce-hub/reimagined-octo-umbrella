package com.voicedock.ai.telecom

import android.content.Context
import android.provider.ContactsContract

/** One phone number of one contact. */
data class ContactEntry(val name: String, val number: String) {
    val display: String get() = "$name · $number"
}

/**
 * v0.9.0 — "মাকে ফোন দাও" → which number?
 *
 * Pure fuzzy matching over whatever the phone's contact list provides, Bangla-aware (the `\p{M}` fix
 * from v0.7 matters here too: Bengali names carry combining marks). Returns candidates; the caller asks
 * when there is more than one. It never picks a number out of the air.
 */
object ContactMatch {

    private val stop = setOf("the", "a", "to", "call", "phone", "কে", "আমি", "আমার", "ফোন", "কল")

    fun tokens(s: String): List<String> = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
        .split(' ')
        .map { it.trim().removeSuffix("কে").removeSuffix("ের") }
        .filter { it.length > 1 && it !in stop }

    /** "মাকে" → "মা"; "Rahim's" → "rahim" is left to the tokeniser. */
    fun cleanName(s: String): String = s.trim()
        .removeSuffix("কে").removeSuffix("ের")
        .trim(',', '.', '?', '!', ' ')

    /** Numbers only, keeping a leading +; rejects anything that cannot be dialled. */
    fun sanitizeNumber(raw: String): String? {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 5 || digits.length > 15) return null
        return (if (plus) "+" else "") + digits
    }

    /**
     * @return matching contacts, best first (exact name first, then prefix, then token overlap).
     */
    fun match(query: String, contacts: List<ContactEntry>, limit: Int = 4): List<ContactEntry> {
        val q = cleanName(query)
        val qt = tokens(q)
        if (qt.isEmpty()) return emptyList()
        val normQ = q.lowercase().replace(Regex("\\s+"), " ").trim()
        val scored = contacts.mapNotNull { c ->
            val name = c.name.lowercase().replace(Regex("\\s+"), " ").trim()
            val hay = tokens(c.name)
            val score = when {
                name == normQ -> 100
                name.startsWith(normQ) -> 80
                hay.any { it == normQ } -> 70
                else -> qt.count { tok -> hay.any { it == tok || it.startsWith(tok) || tok.startsWith(it) } } * 20
            }
            if (score > 0) c to score else null
        }
        return scored.sortedWith(compareByDescending<Pair<ContactEntry, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .distinctBy { it.number }
            .take(limit)
    }

    /** "মা", "ma" and "আম্মু" all resolve through the alias list the user can edit in Settings. */
    fun expandAliases(query: String, aliases: Map<String, String>): String {
        val q = cleanName(query).lowercase()
        return aliases[q] ?: aliases.entries.firstOrNull { q.startsWith(it.key) }?.value ?: query
    }
}

class ContactResolver(private val ctx: Context) {

    /** Cheap read of name + number pairs. Empty when READ_CONTACTS is not granted. */
    fun all(limit: Int = 4000): List<ContactEntry> {
        val out = ArrayList<ContactEntry>(256)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(nameIdx)?.trim().orEmpty()
                    val number = ContactMatch.sanitizeNumber(c.getString(numIdx).orEmpty()) ?: continue
                    if (name.isNotBlank()) out += ContactEntry(name, number)
                }
            }
        }
        return out.distinctBy { it.number }
    }

    fun resolve(query: String): List<ContactEntry> = ContactMatch.match(query, all())
}
