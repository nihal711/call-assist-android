package dev.nihal.callassist

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps the stack trace of the last uncaught exception so it can be read from
 * Settings → Last crash report. The system's "app has a bug" dialog says
 * nothing useful and logcat needs a cable; this makes the next crash
 * self-reporting.
 */
object CrashLog {

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.log")

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Exception) {
                    "?"
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText("$stamp  Call Assist v$version  thread: ${thread.name}\n\n$trace")
            } catch (_: Throwable) {
            }
            // Hand back to the platform so the process still dies the normal way.
            if (previous != null) previous.uncaughtException(thread, e) else exitProcess(10)
        }
    }

    fun read(ctx: Context): String? =
        try {
            file(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
