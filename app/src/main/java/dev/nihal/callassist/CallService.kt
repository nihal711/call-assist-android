package dev.nihal.callassist

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService

class CallService : InCallService() {

    companion object {
        var instance: CallService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val number = call.details.handle?.schemeSpecificPart
        val info = ContactHelper.lookup(this, number)
        val label = info.name ?: number ?: "Unknown"
        val ringing = call.stateCompat() == Call.STATE_RINGING

        if (ringing && AutomationEngine.shouldHandle(this, info.name, number)) {
            AutomationEngine.start(this, call, label)
            return
        }

        OngoingCall.set(call, label, info.photoUri)
        Notifications.showCall(this, label, ringing)
        // As the default dialer's bound InCallService we can bring up the call
        // screen directly; the full-screen notification stays as a fallback.
        try {
            startActivity(
                Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    override fun onCallRemoved(call: Call) {
        OngoingCall.clear(call)
        if (OngoingCall.call == null) Notifications.cancelCall(this)
    }
}
