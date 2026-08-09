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
        var t9: String
    )

    data class Match(val contact: Contact, val number: PhoneEntry, val rank: Int)

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

    fun t9encode(name: String): String = buildString {
        for (c in name.lowercase()) {
            when {
                c.isDigit() -> append(c)
                t9map.containsKey(c) -> append(t9map[c])
                c == ' ' || c == '.' || c == '-' || c == '_' -> append(' ')
            }
        }
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
                    Phone.TYPE, Phone.LABEL, Phone.STARRED
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
                    val digits = number.filter { it.isDigit() }
                    val contact = byId.getOrPut(id) {
                        Contact(id, name, starred, mutableListOf(), t9encode(name))
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
            if (nameSearch) {
                val t9 = contact.t9
                if (t9.startsWith(q)) best = 0
                else {
                    var i = t9.indexOf(' ')
                    while (i >= 0 && best > 0) {
                        if (t9.startsWith(q, i + 1)) best = 0
                        i = t9.indexOf(' ', i + 1)
                    }
                    if (best == Int.MAX_VALUE && t9.replace(" ", "").contains(q)) best = 1
                }
            }
            if (best > 1) {
                for (n in contact.numbers) {
                    if (n.digits.startsWith(q)) {
                        if (best > 2) { best = 2; matched = n }
                    } else if (n.digits.contains(q)) {
                        if (best > 3) { best = 3; matched = n }
                    }
                }
            }
            if (best != Int.MAX_VALUE) out.add(Match(contact, matched, best))
            if (out.size >= 200) break
        }
        return out.sortedWith(compareBy({ it.rank }, { it.contact.name.lowercase() })).take(limit)
    }

    fun lookupNameCached(number: String): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val tail = digits.takeLast(9)
        return contacts.firstOrNull { c -> c.numbers.any { it.digits.endsWith(tail) } }?.name
    }

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
