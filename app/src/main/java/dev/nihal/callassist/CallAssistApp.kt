package dev.nihal.callassist

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class CallAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        applyTheme(this)
        Notifications.ensureChannels(this)
        // If something took the default-dialer role away, gate automation is
        // silently dead — surface it whenever our process comes up.
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !rm.isRoleHeld(RoleManager.ROLE_DIALER) && Prefs.enabled(this)
            ) {
                Notifications.roleLost(this)
            }
        } catch (_: Exception) {
        }
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
