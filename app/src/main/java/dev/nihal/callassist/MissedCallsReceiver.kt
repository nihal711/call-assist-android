package dev.nihal.callassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telecom only posts its own missed-call notification when the default dialer
 * has no receiver for ACTION_SHOW_MISSED_CALLS_NOTIFICATION. CallService already
 * posts ours on a MISSED disconnect, so declaring this is what matters; that
 * broadcast itself needs no handling.
 *
 * Telecom's notification also came back after a reboot, so on BOOT_COMPLETED
 * we re-post the ones still unread.
 */
class MissedCallsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                Notifications.restoreMissed(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
