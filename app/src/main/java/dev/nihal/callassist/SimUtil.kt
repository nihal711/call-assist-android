package dev.nihal.callassist

import android.app.Activity
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import java.util.Locale

object SimUtil {

    fun accounts(ctx: Context): List<PhoneAccountHandle> =
        try {
            ctx.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts
        } catch (_: SecurityException) {
            emptyList()
        }

    fun label(ctx: Context, h: PhoneAccountHandle, i: Int): String =
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val acct = tm.getPhoneAccount(h)
            val label = acct?.label?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${i + 1}"
            val addr = acct?.address?.schemeSpecificPart
            if (!addr.isNullOrBlank() && !label.contains(addr)) {
                val pretty = PhoneNumberUtils.formatNumber(addr, Locale.getDefault().country) ?: addr
                "$label ($pretty)"
            } else label
        } catch (_: Exception) {
            "SIM ${i + 1}"
        }

    fun showPicker(activity: Activity, onDone: () -> Unit) {
        val accts = accounts(activity)
        val labels = mutableListOf("System default", "Ask every time")
        accts.forEachIndexed { i, h -> labels.add(label(activity, h, i)) }
        Sheet(activity)
            .title("Default SIM for calls")
            .items(labels) { which ->
                when (which) {
                    0 -> Prefs.setSim(activity, Prefs.SIM_SYSTEM, "", "System default")
                    1 -> Prefs.setSim(activity, Prefs.SIM_ASK, "", "Ask every time")
                    else -> {
                        val h = accts[which - 2]
                        Prefs.setSim(activity, Prefs.SIM_FIXED, h.id, labels[which])
                    }
                }
                onDone()
            }
            .negative()
            .show()
    }
}
