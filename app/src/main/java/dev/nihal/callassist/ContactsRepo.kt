package dev.nihal.callassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone

object ContactsRepo {

    data class PhoneEntry(val number: String, val label: String, val digits: String)
    data class Contact(
        val id: Long,
        val name: String,
        val starred: Boolean,
        val numbers: MutableList<PhoneEntry>,
        var t9: String,
        var t9Map: IntArray,
        val photoUri: String? = null
    )

    data class Match(
        val contact: Contact,
        val number: PhoneEntry,
        val rank: Int,
        val nameSpan: IntRange? = null,   // char range in contact.name that matched
        val digitSpan: IntRange? = null   // digit-index range in number.digits that matched
    )

    data class CallEntry(
        val number: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val duration: Long
    )

    @Volatile
    var contacts: List<Contact> = emptyList()
        private set

    private val t9map: Map<Char, Char> = buildMap {
        "abc".forEach { put(it, '2') }
        "def".forEach { put(it, '3') }
        "ghi".forEach { put(it, '4') }
        "jkl".forEach { put(it, '5') }
        "mno".forEach { put(it, '6') }
        "pqrs".forEach { put(it, '7') }
        "tuv".forEach { put(it, '8') }
        "wxyz".forEach { put(it, '9') }
    }

    /** Returns the T9 encoding plus a map from each T9 char to its index in the name. */
    fun t9encode(name: String): Pair<String, IntArray> {
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        val lower = name.lowercase()
        for (i in lower.indices) {
            val c = lower[i]
            when {
                c.isDigit() -> { sb.append(c); map.add(i) }
                t9map.containsKey(c) -> { sb.append(t9map[c]!!); map.add(i) }
                c == ' ' || c == '.' || c == '-' || c == '_' -> { sb.append(' '); map.add(i) }
            }
        }
        return sb.toString() to map.toIntArray()
    }

    private fun has(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Blocking; call from a background thread. */
    fun load(ctx: Context) {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return
        val byId = LinkedHashMap<Long, Contact>()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(
                    Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER,
                    Phone.TYPE, Phone.LABEL, Phone.STARRED, Phone.PHOTO_URI
                ),
                null, null,
                Phone.STARRED + " DESC, " + Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    val type = c.getInt(3)
                    val label = Phone.getTypeLabel(ctx.resources, type, c.getString(4)).toString()
                    val starred = c.getInt(5) == 1
                    val photo = c.getString(6)
                    val digits = number.filter { it.isDigit() }
                    val contact = byId.getOrPut(id) {
                        val (t9, map) = t9encode(name)
                        Contact(id, name, starred, mutableListOf(), t9, map, photo)
                    }
                    if (contact.numbers.none { it.digits == digits && digits.isNotEmpty() }) {
                        contact.numbers.add(PhoneEntry(number, label, digits))
                    }
                }
            }
        } catch (_: Exception) {
        }
        contacts = byId.values.filter { it.numbers.isNotEmpty() }
    }

    /**
     * T9 search: matches contact names via keypad letters and numbers by digits.
     * Rank: 0 = name word-prefix match, 1 = name match elsewhere,
     *       2 = number prefix, 3 = number substring.
     */
    fun search(query: String, limit: Int = 20): List<Match> {
        val q = query.filter { it.isDigit() }
        if (q.isEmpty()) return emptyList()
        val nameSearch = query.all { it.isDigit() }
        val out = ArrayList<Match>()
        for (contact in contacts) {
            var best = Int.MAX_VALUE
            var matched = contact.numbers.first()
            var nameSpan: IntRange? = null
            var digitSpan: IntRange? = null
            if (nameSearch && q.length <= contact.t9.length) {
                val t9 = contact.t9
                var hit = -1
                if (t9.startsWith(q)) hit = 0
                else {
                    var i = t9.indexOf(' ')
                    while (i >= 0 && hit < 0) {
                        if (t9.startsWith(q, i + 1)) hit = i + 1
                        i = t9.indexOf(' ', i + 1)
                    }
                }
                if (hit >= 0) {
                    best = 0
                } else {
                    // substring match that doesn't cross a word boundary
                    var i = t9.indexOf(q)
                    while (i >= 0) {
                        if (!t9.substring(i, i + q.length).contains(' ')) {
                            hit = i
                            best = 1
                            break
                        }
                        i = t9.indexOf(q, i + 1)
                    }
                }
                if (hit >= 0) {
                    nameSpan = contact.t9Map[hit]..contact.t9Map[hit + q.length - 1]
                }
            }
            if (best > 1) {
                for (n in contact.numbers) {
                    if (n.digits.startsWith(q)) {
                        if (best > 2) {
                            best = 2; matched = n; digitSpan = 0 until q.length
                        }
                    } else {
                        val i = n.digits.indexOf(q)
                        if (i >= 0 && best > 3) {
                            best = 3; matched = n; digitSpan = i until i + q.length
                        }
                    }
                }
            }
            if (best != Int.MAX_VALUE) out.add(Match(contact, matched, best, nameSpan, digitSpan))
            if (out.size >= 200) break
        }
        return out.sortedWith(compareBy({ it.rank }, { it.contact.name.lowercase() })).take(limit)
    }

    fun lookupCached(number: String): Contact? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val tail = digits.takeLast(9)
        return contacts.firstOrNull { c -> c.numbers.any { it.digits.endsWith(tail) } }
    }

    fun lookupNameCached(number: String): String? = lookupCached(number)?.name

    /** Blocking; call from a background thread. */
    fun loadCallLog(ctx: Context, limit: Int = 300): List<CallEntry> {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<CallEntry>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
                ),
                null, null,
                CallLog.Calls.DATE + " DESC LIMIT $limit"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CallEntry(
                            number = c.getString(0) ?: "",
                            name = c.getString(1),
                            type = c.getInt(2),
                            date = c.getLong(3),
                            duration = c.getLong(4)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
