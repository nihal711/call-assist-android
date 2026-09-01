package dev.nihal.callassist

import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.os.Handler
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
        val ringing = call.stateCompat() == Call.STATE_RINGING

        // Number match needs no contacts query — decide instantly on the main thread.
        if (ringing && AutomationEngine.numberMatches(this, number)) {
            AutomationEngine.start(this, call, number ?: "intercom")
            return
        }

        // Contact lookup does disk I/O — keep it off the ring path's main thread.
        Thread {
            val info = ContactHelper.lookup(this, number)
            val label = info.name ?: number ?: "Unknown"
            val photo = ContactHelper.loadPhoto(this, info.photoUri)
            Handler(mainLooper).post {
                val state = call.stateCompat()
                // Hung up while the contact lookup ran: nothing to show, and
                // telecom would reject a call notification for it.
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) return@post
                if (state == Call.STATE_RINGING && AutomationEngine.nameMatches(this, info.name)) {
                    AutomationEngine.start(this, call, label)
                    return@post
                }
                OngoingCall.set(call, label, info.photoUri)
                // Conventional dialer behaviour, done deterministically (Samsung phones don't reliably
                // fire our full-screen intent while locked):
                //  - locked / screen off → we show the call screen ourselves and post the
                //    notification quietly so no heads-up banner stacks on top of it;
                //  - unlocked & in use → heads-up with Answer/Decline via the system.
                val ringing = state == Call.STATE_RINGING
                val km = getSystemService(KeyguardManager::class.java)
                val pm = getSystemService(PowerManager::class.java)
                val deviceInUse = pm.isInteractive && !km.isKeyguardLocked
                val sim = simLabel(call)
                if (ringing && deviceInUse) {
                    Notifications.showCall(this, label, incoming = true, number = number, photo = photo, sim = sim)
                    return@post
                }
                Notifications.showCall(
                    this, label, incoming = ringing, quiet = ringing, number = number, photo = photo, sim = sim
                )
                try {
                    startActivity(
                        Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    override fun onCallRemoved(call: Call) {
        OngoingCall.clear(call)
        if (OngoingCall.call != null) return
        // Surface a remaining call (e.g. one that was on hold behind the removed
        // one) instead of leaving the UI empty.
        val remaining = calls.firstOrNull { it.stateCompat() != Call.STATE_DISCONNECTED }
        if (remaining == null) {
            Notifications.cancelCall(this)
            return
        }
        val number = remaining.details.handle?.schemeSpecificPart
        Thread {
            val info = ContactHelper.lookup(this, number)
            val photo = ContactHelper.loadPhoto(this, info.photoUri)
            Handler(mainLooper).post {
                val st = remaining.stateCompat()
                if (calls.contains(remaining) && st != Call.STATE_DISCONNECTED && st != Call.STATE_DISCONNECTING) {
                    OngoingCall.set(remaining, info.name ?: number ?: "Unknown", info.photoUri)
                    Notifications.showCall(
                        this, OngoingCall.label, remaining.stateCompat() == Call.STATE_RINGING,
                        number = number, photo = photo, sim = simLabel(remaining)
                    )
                }
            }
        }.start()
    }

    /** "SIM 1 · Singtel" for the notification, or null on single-SIM phones. */
    private fun simLabel(call: Call): String? {
        if (!SimUtil.isDual(this)) return null
        val sim = SimUtil.byHandle(this, call.details.accountHandle) ?: return null
        return "SIM ${sim.slot + 1} · ${sim.name}"
    }
}
