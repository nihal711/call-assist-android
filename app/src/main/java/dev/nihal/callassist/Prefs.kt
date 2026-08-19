package dev.nihal.callassist

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    // Generic on purpose — the building name isn't the app's to assume. Anyone
    // already running the app keeps whatever they saved; this only seeds a
    // fresh install, and the Gate tab lets them edit it.
    const val DEFAULT_CONTACT = "Intercom"
    const val DEFAULT_CODE = "6#"

    // How long to give the gate before assuming the code didn't land. Intercoms
    // vary a lot — some drop the call in 2s, some take 8-10s — so this is tunable
    // from Settings. Too short and we re-send tones into a gate that is already
    // acting on the first code.
    const val DEFAULT_RETRY_WAIT_S = 5
    const val DEFAULT_MAX_ATTEMPTS = 3
    val RETRY_WAIT_CHOICES = intArrayOf(3, 5, 7, 10, 15)
    val MAX_ATTEMPT_CHOICES = intArrayOf(1, 2, 3, 4, 5)

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("callassist", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun contactName(ctx: Context): String = sp(ctx).getString("contact", DEFAULT_CONTACT)!!
    fun setContactName(ctx: Context, v: String) = sp(ctx).edit().putString("contact", v).apply()

    fun gateCode(ctx: Context): String = sp(ctx).getString("code", DEFAULT_CODE)!!
    fun setGateCode(ctx: Context, v: String) = sp(ctx).edit().putString("code", v).apply()

    /** Seconds to wait after a code before deciding the gate didn't take it. */
    fun retryWaitSeconds(ctx: Context): Int =
        sp(ctx).getInt("retryWaitS", DEFAULT_RETRY_WAIT_S).coerceIn(1, 30)

    fun setRetryWaitSeconds(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("retryWaitS", v.coerceIn(1, 30)).apply()

    /** Total times the code is sent, including the first. */
    fun maxAttempts(ctx: Context): Int =
        sp(ctx).getInt("maxAttempts", DEFAULT_MAX_ATTEMPTS).coerceIn(1, 5)

    fun setMaxAttempts(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("maxAttempts", v.coerceIn(1, 5)).apply()

    fun notifyGate(ctx: Context): Boolean = sp(ctx).getBoolean("notifyGate", true)
    fun setNotifyGate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notifyGate", v).apply()

    // "dark" (default), "light", "system"
    fun themeMode(ctx: Context): String = sp(ctx).getString("themeMode", "dark")!!
    fun setThemeMode(ctx: Context, v: String) = sp(ctx).edit().putString("themeMode", v).apply()

    fun confirmCall(ctx: Context): Boolean = sp(ctx).getBoolean("confirmCall", true)
    fun setConfirmCall(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("confirmCall", v).apply()

    fun keyTones(ctx: Context): Boolean = sp(ctx).getBoolean("keyTones", true)
    fun setKeyTones(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("keyTones", v).apply()

    fun keyHaptics(ctx: Context): Boolean = sp(ctx).getBoolean("keyHaptics", true)
    fun setKeyHaptics(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("keyHaptics", v).apply()

    private val DEFAULT_REPLIES = arrayOf(
        "Can't talk right now — I'll call you back.",
        "I'm on my way.",
        "Text me?"
    )

    fun quickReplies(ctx: Context): Array<String> =
        Array(3) { i -> sp(ctx).getString("reply$i", DEFAULT_REPLIES[i])!! }

    fun setQuickReply(ctx: Context, i: Int, v: String) =
        sp(ctx).edit().putString("reply$i", v.ifBlank { DEFAULT_REPLIES[i] }).apply()

    const val SIM_SYSTEM = "system"
    const val SIM_ASK = "ask"
    const val SIM_FIXED = "fixed"

    fun simMode(ctx: Context): String = sp(ctx).getString("simMode", SIM_SYSTEM)!!
    fun simId(ctx: Context): String = sp(ctx).getString("simId", "")!!
    fun simLabel(ctx: Context): String = sp(ctx).getString("simLabel", "System default")!!
    fun setSim(ctx: Context, mode: String, id: String, label: String) =
        sp(ctx).edit().putString("simMode", mode).putString("simId", id)
            .putString("simLabel", label).apply()

    private fun logFile(ctx: Context) = File(ctx.filesDir, "events.log")

    /**
     * Lines are written as "<epochMillis>\t<message>" so the Gate tab can group
     * them into sessions and render relative day labels. Older builds wrote
     * "MMM d HH:mm:ss  <message>" with no year — [readLogEntries] still parses
     * those, but their timestamp is only best-effort.
     */
    @Synchronized
    fun log(ctx: Context, msg: String) {
        val f = logFile(ctx)
        f.appendText("${System.currentTimeMillis()}\t$msg\n")
        if (f.length() > 64 * 1024) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
        }
    }

    data class LogEntry(val time: Long, val message: String)

    /** Oldest first. */
    fun readLogEntries(ctx: Context): List<LogEntry> {
        val f = logFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(200).mapNotNull { parseLogLine(it) }
    }

    private val legacyFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

    private fun parseLogLine(line: String): LogEntry? {
        if (line.isBlank()) return null
        val tab = line.indexOf('\t')
        if (tab > 0) {
            val ts = line.substring(0, tab).toLongOrNull()
            if (ts != null) return LogEntry(ts, line.substring(tab + 1).trim())
        }
        // Legacy "MMM d HH:mm:ss  message" — no year, so assume the current one
        // and roll back if that would put the entry in the future.
        val sep = line.indexOf("  ")
        if (sep <= 0) return LogEntry(0L, line.trim())
        val stamp = line.substring(0, sep).trim()
        val msg = line.substring(sep + 2).trim()
        val parsed = try {
            legacyFmt.parse(stamp)
        } catch (_: Exception) {
            null
        } ?: return LogEntry(0L, msg)
        val cal = java.util.Calendar.getInstance().apply { time = parsed }
        val now = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR))
        if (cal.after(now)) cal.add(java.util.Calendar.YEAR, -1)
        return LogEntry(cal.timeInMillis, msg)
    }

    fun clearLog(ctx: Context) {
        logFile(ctx).delete()
    }
}
