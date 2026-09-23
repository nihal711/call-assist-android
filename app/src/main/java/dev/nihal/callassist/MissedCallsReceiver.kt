package dev.nihal.callassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telecom only posts its own missed-call notification when the default dialer
 * has no receiver for ACTION_SHOW_MISSED_CALLS_NOTIFICATION. CallService already
 * posts ours on a MISSED disconnect, so declaring this is what matters; the
 * broadcast itself needs no handling.
 */
class MissedCallsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
