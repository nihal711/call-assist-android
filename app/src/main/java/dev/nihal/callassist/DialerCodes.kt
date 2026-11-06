package dev.nihal.callassist

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast

/**
 * Codes the phone handles itself instead of sending to the network. Placing
 * them as a call hands them to the carrier as USSD, which answers "Connection
 * problem or invalid MMI code". A dialer is expected to catch them first:
 *
 *  - `*#06#` shows the IMEI.
 *  - `*#*#4636#*#*` style codes go out as the system secret-code broadcast.
 *  - `*#0228#` style service codes go out the same way, but only when an app
 *    on the phone registered for that exact code. Everything else (`*#21#`,
 *    `*100#`…) still reaches the network as a normal MMI request.
 *
 * Only keypad input is checked, never tel: links, so a web page can't fire a
 * service menu.
 */
object DialerCodes {

    private val SECRET = Regex("""\*#\*#([0-9*]+)#\*#\*""")
    private val SHORT = Regex("""\*#([0-9*]+)#""")

    /** GSM supplementary-service codes: `*#code#` asks the network for their status. */
    private val NETWORK_SERVICES = setOf(
        "002", "004", "21", "61", "62", "67", "03", "30", "31", "33", "35",
        "43", "76", "77", "300", "330", "331", "332", "333", "351", "353"
    )

    private const val SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE"

    /** Runs [input] if it's a local code. Returns true when it was handled. */
    fun handle(activity: Activity, input: String): Boolean {
        if (input == "*#06#") {
            showImei(activity)
            return true
        }
        SECRET.matchEntire(input)?.let {
            sendSecretCode(activity, it.groupValues[1])
            return true
        }
        SHORT.matchEntire(input)?.let {
            val code = it.groupValues[1]
            if (code !in NETWORK_SERVICES && hasReceiver(activity, code)) {
                sendSecretCode(activity, code)
                return true
            }
        }
        return false
    }

    /**
     * True when an installed app registered for exactly [code]. Apps that listen
     * for every code (Google's dialer does) don't count, so they can't pull
     * network requests like `*#21#` off the network.
     */
    private fun hasReceiver(activity: Activity, code: String): Boolean = try {
        val intent = Intent(SECRET_CODE_ACTION, Uri.parse("android_secret_code://$code"))
        activity.packageManager
            .queryBroadcastReceivers(intent, PackageManager.GET_RESOLVED_FILTER)
            .any { info ->
                info.filter?.authoritiesIterator()?.asSequence()?.any { it.host == code } == true
            }
    } catch (_: Exception) {
        false
    }

    private fun sendSecretCode(activity: Activity, code: String) {
        try {
            // Sent by the phone process; only the default dialer may ask for it.
            activity.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(code)
        } catch (_: SecurityException) {
            Toast.makeText(activity, "Set Call Assist as the default phone app to use this code", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not run code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImei(activity: Activity) {
        val imeis = readImeis(activity)
        if (imeis.isNotEmpty()) {
            val text = if (imeis.size == 1) imeis.first()
            else imeis.mapIndexed { i, v -> "SIM ${i + 1}: $v" }.joinToString("\n")
            GlassConfirm(activity)
                .title(if (imeis.size == 1) "IMEI" else "IMEIs")
                .message(text)
                .negative("Copy") {
                    activity.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("IMEI", imeis.joinToString("\n")))
                }
                .positive("Done")
                .show()
            return
        }
        // Some phones register *#06# themselves; let them show their own screen.
        if (hasReceiver(activity, "06")) {
            sendSecretCode(activity, "06")
            return
        }
        // OnePlus, OPPO and realme name the page "About device".
        val page = if (Build.MANUFACTURER.lowercase() in setOf("oneplus", "oppo", "realme")) "About device"
        else "About phone"
        GlassConfirm(activity)
            .title("IMEI")
            .message("Android only lets the phone's built-in dialer read the IMEI. You'll find it in Settings → $page.")
            .negative()
            .positive(page) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                } catch (_: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    /** Empty when Android withholds the IMEI (any non-system app since Android 10). */
    private fun readImeis(activity: Activity): List<String> {
        if (activity.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val tm = activity.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        val slots = tm.phoneCount.coerceAtLeast(1)
        return (0 until slots).mapNotNull { slot ->
            try {
                tm.getImei(slot)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
