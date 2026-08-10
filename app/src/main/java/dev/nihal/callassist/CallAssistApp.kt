package dev.nihal.callassist

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class CallAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
    }

    companion object {
        fun applyTheme(ctx: Context) {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.themeMode(ctx)) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}
