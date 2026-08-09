package dev.nihal.callassist

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    const val DEFAULT_CONTACT = "Intercom"
    const val DEFAULT_CODE = "6#"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("callassist", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun contactName(ctx: Context): String = sp(ctx).getString("contact", DEFAULT_CONTACT)!!
    fun setContactName(ctx: Context, v: String) = sp(ctx).edit().putString("contact", v).apply()

    fun gateCode(ctx: Context): String = sp(ctx).getString("code", DEFAULT_CODE)!!
    fun setGateCode(ctx: Context, v: String) = sp(ctx).edit().putString("code", v).apply()

    fun notifyGate(ctx: Context): Boolean = sp(ctx).getBoolean("notifyGate", true)
    fun setNotifyGate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notifyGate", v).apply()

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

    @Synchronized
    fun log(ctx: Context, msg: String) {
        val ts = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        val f = logFile(ctx)
        f.appendText("$ts  $msg\n")
        if (f.length() > 64 * 1024) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
        }
    }

    fun readLog(ctx: Context): String {
        val f = logFile(ctx)
        if (!f.exists()) return "(no events yet)"
        return f.readLines().takeLast(100).reversed().joinToString("\n")
    }

    fun clearLog(ctx: Context) {
        logFile(ctx).delete()
    }
}
