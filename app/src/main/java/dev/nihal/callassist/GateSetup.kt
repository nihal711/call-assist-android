package dev.nihal.callassist

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * The Gate tab's readiness checklist: what has to be true for the intercom
 * automation to run, plus the fix-it actions behind each row. Pure move out
 * of MainActivity — the automation itself lives in AutomationEngine.
 */
object GateSetup {

    /**
     * One thing that has to be true for the gate to open. [blocking] separates
     * "automation cannot run" from "it runs, but something is degraded" — the
     * headline reports the worst of the two, and only the failures get a row.
     */
    data class Check(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val blocking: Boolean,
        val fix: (() -> Unit)?
    )

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.toTypedArray()
    }

    fun checks(activity: Activity, requestRole: () -> Unit, requestPerms: () -> Unit): List<Check> {
        fun has(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val rm = activity.getSystemService(RoleManager::class.java)
        val fsi = Build.VERSION.SDK_INT < 34 ||
            activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val missingPerms = mutableListOf<String>().apply {
            if (!has(Manifest.permission.READ_CONTACTS)) add("Contacts")
            if (!has(Manifest.permission.READ_CALL_LOG)) add("Call log")
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) add("Notifications")
        }
        return listOf(
            Check(
                "Set as default phone app",
                "Required — the gate can't be answered without it",
                rm.isRoleHeld(RoleManager.ROLE_DIALER),
                blocking = true,
                fix = requestRole
            ),
            Check(
                "Grant permissions",
                if (missingPerms.isEmpty()) "" else "${missingPerms.joinToString(", ")} — tap to allow",
                missingPerms.isEmpty(),
                blocking = true,
                fix = requestPerms
            ),
            Check(
                "Turn on automation",
                "Auto-answer is switched off below",
                Prefs.enabled(activity),
                blocking = true,
                fix = null
            ),
            Check(
                "Allow full-screen call alerts",
                "Calls show as a banner only",
                fsi,
                blocking = false,
                fix = { openFsiSettings(activity) }
            ),
            Check(
                "Exempt from battery optimisation",
                "Android may delay or kill the app",
                activity.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(activity.packageName),
                blocking = false,
                fix = { openBatterySettings(activity) }
            )
        )
    }

    /** Android 14+ full-screen-intent permission page. */
    fun openFsiSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Open Settings → Apps → Call Assist → Notifications", Toast.LENGTH_LONG).show()
        }
    }

    fun openBatterySettings(activity: Activity) {
        // The direct request dialog, as in Settings: the system's own list shows
        // the default phone app as already unrestricted with its switch locked,
        // yet never adds it to the exemption list this check reads. The list is
        // only the fallback for devices without the dialog.
        try {
            @Suppress("BatteryLife")
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
            return
        } catch (_: Exception) {
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(activity, "Open Settings → Apps → Call Assist → Battery", Toast.LENGTH_LONG).show()
        }
    }
}
