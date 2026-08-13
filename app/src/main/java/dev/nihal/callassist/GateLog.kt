package dev.nihal.callassist

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Turns the flat event log into one card per gate run so the Gate tab is
 * readable at a glance: outcome first, then the individual steps.
 *
 * A run starts at an "Incoming call from …" line and ends at the next one, so
 * every line lands in exactly one session and nothing is dropped. Lines logged
 * before the first "Incoming call" (older log tails) are kept as a session with
 * no headline call.
 */
object GateLog {

    enum class Outcome { OPENED, WARNING, RUNNING }

    data class Step(val time: Long, val text: String)

    data class Session(
        val start: Long,
        val end: Long,
        val label: String?,
        val code: String?,
        val outcome: Outcome,
        val steps: List<Step>
    )

    sealed class Item {
        data class Header(val text: String) : Item()
        data class Entry(val session: Session, val bg: Int, val divider: Boolean) : Item()
    }

    private const val START_MARKER = "Incoming call from "

    fun sessions(entries: List<Prefs.LogEntry>): List<Session> {
        val chunks = mutableListOf<MutableList<Prefs.LogEntry>>()
        for (e in entries) {
            if (e.message.startsWith(START_MARKER) || chunks.isEmpty()) {
                chunks.add(mutableListOf(e))
            } else {
                chunks.last().add(e)
            }
        }
        return chunks.map { toSession(it) }.sortedByDescending { it.start }
    }

    private fun toSession(lines: List<Prefs.LogEntry>): Session {
        val label = lines.firstOrNull { it.message.startsWith(START_MARKER) }
            ?.let { quoted(it.message) }
        // Newer runs log the code on the "Attempt n/N" line; older ones logged it
        // on "Call active — sending "6#"".
        val code = lines.firstOrNull { it.message.startsWith("Attempt ") }
            ?.let { quoted(it.message) }
            ?: lines.firstOrNull { it.message.startsWith("Call active — sending") }
                ?.let { quoted(it.message) }

        // "…hung up — done" and "…hung up after 12s — done" both count.
        val finished = lines.any {
            (it.message.contains("hung up") && it.message.contains("— done")) ||
                it.message.contains("ended")
        }
        val warned = lines.any {
            it.message.contains("may NOT have opened") ||
                it.message.startsWith("Failsafe:") ||
                it.message.contains("could not mute")
        }
        val outcome = when {
            warned -> Outcome.WARNING
            finished -> Outcome.OPENED
            else -> Outcome.RUNNING
        }

        return Session(
            start = lines.first().time,
            end = lines.last().time,
            label = label,
            code = code,
            outcome = outcome,
            steps = lines.map { Step(it.time, stepText(it.message)) }
        )
    }

    /** First double-quoted run in a message, e.g. the contact name or the code. */
    private fun quoted(msg: String): String? {
        val a = msg.indexOf('"')
        if (a < 0) return null
        val b = msg.indexOf('"', a + 1)
        return if (b > a) msg.substring(a + 1, b) else null
    }

    /**
     * Shortens each raw line for the step list — the contact name is already in
     * the card title, so repeating it in every row is just noise.
     */
    private fun stepText(msg: String): String = when {
        msg.startsWith(START_MARKER) -> "Answered automatically"
        msg.startsWith("Call active — mic muted") -> "Mic muted"
        msg.startsWith("Call active — could not mute") ->
            "Could not mute mic" + (afterParen(msg)?.let { " ($it)" } ?: "")
        msg.startsWith("Call active — sending") -> "Sending code"
        msg.startsWith("Attempt ") -> {
            // "Attempt 1/3 — sending "6#"" -> "Sending code "6#" (try 1/3)"
            val n = msg.removePrefix("Attempt ").substringBefore(' ')
            val code = quoted(msg)
            if (code != null) "Sending code \"$code\" (try $n)" else msg
        }
        msg.startsWith("Code sent") -> {
            val tones = Regex("\\((\\d+) tones?\\)").find(msg)?.groupValues?.get(1)
            val wait = Regex("waiting (\\d+)s").find(msg)?.groupValues?.get(1)
            buildString {
                append("Code sent")
                if (tones != null) append(" ($tones tones)")
                if (wait != null) append(" — waiting ${wait}s for the gate")
            }
        }
        msg.startsWith("Still connected") -> {
            val secs = Regex("Still connected (\\d+)s").find(msg)?.groupValues?.get(1)
            if (secs != null) "Still connected after ${secs}s — gate didn't answer, retrying"
            else "No answer from gate — retrying"
        }
        msg.startsWith("No response after") -> {
            val secs = Regex("hanging up in (\\d+)s").find(msg)?.groupValues?.get(1)
            "All attempts used — hanging up" + (secs?.let { " in ${it}s" } ?: "")
        }
        msg.contains("hung up") && msg.contains("— done") -> {
            val secs = Regex("after (\\d+)s").find(msg)?.groupValues?.get(1)
            "Intercom hung up" + (secs?.let { " after ${it}s" } ?: "") + " — code accepted"
        }
        msg.contains("ended") -> "Call ended"
        msg.contains("may NOT have opened") -> {
            val secs = Regex("after (\\d+)s").find(msg)?.groupValues?.get(1)
            "We hung up" + (secs?.let { " after ${it}s" } ?: "") + " — gate may not have opened"
        }
        msg.startsWith("Failsafe:") -> "Failsafe hang-up (call ran too long)"
        msg.startsWith("Another call is in progress") -> "Held your other call"
        msg.startsWith("Resumed the held call") -> "Resumed your other call"
        else -> msg
    }

    private fun afterParen(msg: String): String? {
        val a = msg.indexOf('(')
        val b = msg.lastIndexOf(')')
        return if (a in 0 until b) msg.substring(a + 1, b) else null
    }

    fun dayLabel(ts: Long): String {
        if (ts <= 0L) return "Earlier"
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today"
            sameYear && dayDiff == 1 -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ts))
        }
    }

    /** Groups sessions under day headers with Recents-style rounded card runs. */
    fun buildItems(sessions: List<Session>): List<Item> {
        val items = mutableListOf<Item>()
        var i = 0
        while (i < sessions.size) {
            val label = dayLabel(sessions[i].start)
            var j = i
            while (j < sessions.size && dayLabel(sessions[j].start) == label) j++
            items.add(Item.Header(label))
            for (k in i until j) {
                val single = j - i == 1
                val bg = when {
                    single -> R.drawable.bg_group_single
                    k == i -> R.drawable.bg_group_top
                    k == j - 1 -> R.drawable.bg_group_bottom
                    else -> R.drawable.bg_group_mid
                }
                items.add(Item.Entry(sessions[k], bg, divider = k < j - 1))
            }
            i = j
        }
        return items
    }
}
