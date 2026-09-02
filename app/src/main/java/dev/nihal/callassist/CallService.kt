package dev.nihal.callassist

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService

class CallService : InCallService() {

    companion object {
        var instance: CallService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Screen-off-on-ear (proximity) ----
    //
    // Owned here, not by the call screen: a PROXIMITY_SCREEN_OFF_WAKE_LOCK is
    // system-wide — while held, covering the sensor blanks the screen in ANY
    // app — and this process never dies, so a leak from an activity teardown
    // race left phones blanking on the sensor long after calls ended. Every
    // call-ended path runs through this service, so the lock cannot outlive
    // the call list; a forced fallback release covers a sensor that never
    // reports "far" (pocket, face-down).
    private var proximity: PowerManager.WakeLock? = null
    private val proximityForceRelease = Runnable {
        try {
            if (proximity?.isHeld == true) proximity?.release()
        } catch (_: Exception) {
        }
    }

    private fun updateProximity() {
        val pm = getSystemService(PowerManager::class.java)
        if (proximity == null) {
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "callassist:proximity")
                // One release must always fully release, whatever the acquire history.
                .apply { setReferenceCounted(false) }
        }
        val lock = proximity ?: return
        // The intercom's auto-answered call never binds our UI and rings with
        // the screen off; it reaches here too, but only via the same rules.
        val live = calls.any {
            val st = it.stateCompat()
            st == Call.STATE_ACTIVE || st == Call.STATE_DIALING || st == Call.STATE_CONNECTING
        }
        val route = callAudioState?.route
        val onEar = route == null || route == CallAudioState.ROUTE_EARPIECE
        mainHandler.removeCallbacks(proximityForceRelease)
        try {
            if (live && onEar) {
                if (!lock.isHeld) lock.acquire(3 * 60 * 60 * 1000L)
            } else if (lock.isHeld) {
                if (live) {
                    // Mid-call route change (speaker/Bluetooth): screen back now.
                    lock.release()
                } else {
                    // Call over: wait for the phone to leave the ear, then a
                    // hard stop in case the sensor stays covered.
                    lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                    mainHandler.postDelayed(proximityForceRelease, 10_000)
                }
            }
        } catch (_: Exception) {
        }
    }

    // What the current call notification was built from, so it can be
    // re-posted as the call's state or audio route changes.
    private var shownCall: Call? = null
    private var shownLabel = ""
    private var shownNumber: String? = null
    private var shownPhoto: Bitmap? = null
    private var shownQuiet = false

    /** Name + photo per live call, so a swap can put the other call on screen without a re-lookup. */
    private val known = HashMap<Call, Pair<String, android.net.Uri?>>()

    fun labelFor(call: Call): String? = known[call]?.first

    /** Makes [call] the one InCallActivity and the notification show (after a swap). */
    fun promote(call: Call) {
        val info = known[call]
        if (info != null) {
            OngoingCall.set(call, info.first, info.second)
            showNotification(call, info.first, call.details.handle?.schemeSpecificPart, null, quiet = true)
            return
        }
        val number = call.details.handle?.schemeSpecificPart
        Thread {
            val i = ContactHelper.lookup(this, number)
            val label = i.name ?: number ?: "Unknown"
            val photo = ContactHelper.loadPhoto(this, i.photoUri)
            Handler(mainLooper).post {
                if (call.stateCompat() == Call.STATE_DISCONNECTED) return@post
                known[call] = label to i.photoUri
                OngoingCall.set(call, label, i.photoUri)
                showNotification(call, label, number, photo, quiet = true)
            }
        }.start()
    }

    private val stateCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call != shownCall) return
            updateProximity()
            // Ringing → active swaps Answer/Decline for Hang up/Mute/Speaker.
            refreshNotification()
            // MainActivity's return-to-call banner and InCallActivity follow this.
            OngoingCall.notifyChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        clearNotification()
        mainHandler.removeCallbacks(proximityForceRelease)
        try {
            if (proximity?.isHeld == true) proximity?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        updateProximity()
        // Mute / Speaker labels in the notification, toggle state on the call screen.
        refreshNotification()
        OngoingCall.notifyChanged()
    }

    override fun onCallAdded(call: Call) {
        updateProximity()
        val number = call.details.handle?.schemeSpecificPart
        val ringing = call.stateCompat() == Call.STATE_RINGING

        // Number match needs no contacts query — decide instantly on the main thread.
        if (ringing && AutomationEngine.numberMatches(this, number)) {
            AutomationEngine.start(this, call, number ?: "intercom")
            return
        }

        // Opt-in: a withheld/private caller is rejected before anything shows.
        // The intercom is matched above by its number, so this can't touch it.
        if (ringing && number.isNullOrBlank() && Prefs.blockUnknown(this)) {
            try {
                call.reject(false, null)
            } catch (_: Exception) {
            }
            return
        }

        // Contact lookup does disk I/O — keep it off the ring path's main thread.
        Thread {
            val info = ContactHelper.lookup(this, number)
            val conference = call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
            val label = if (conference) "Conference call" else info.name ?: number ?: "Unknown"
            val photo = ContactHelper.loadPhoto(this, info.photoUri)
            Handler(mainLooper).post {
                val state = call.stateCompat()
                // Hung up while the contact lookup ran: nothing to show.
                if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) return@post
                if (state == Call.STATE_RINGING && AutomationEngine.nameMatches(this, info.name)) {
                    AutomationEngine.start(this, call, label)
                    return@post
                }
                known[call] = label to info.photoUri
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
                val headsUp = ringing && deviceInUse
                showNotification(call, label, number, photo, quiet = !headsUp)
                if (headsUp) return@post
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
        updateProximity()
        // Rang out or the caller gave up — leave a missed-call record.
        // (Rejected/blocked calls carry different causes; the intercom is answered.)
        if (call.details.disconnectCause?.code == DisconnectCause.MISSED) {
            val missedNumber = call.details.handle?.schemeSpecificPart
            Thread {
                val info = ContactHelper.lookup(this, missedNumber)
                val photo = ContactHelper.loadPhoto(this, info.photoUri)
                Notifications.missedCall(this, info.name ?: missedNumber ?: "Unknown", missedNumber, photo)
            }.start()
        }
        known.remove(call)
        OngoingCall.clear(call)
        if (OngoingCall.call != null) return
        // Surface a remaining call (e.g. one that was on hold behind the removed
        // one) instead of leaving the UI empty.
        val remaining = calls.firstOrNull { it.stateCompat() != Call.STATE_DISCONNECTED }
        if (remaining == null) {
            clearNotification()
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
                    showNotification(remaining, OngoingCall.label, number, photo, quiet = false)
                }
            }
        }.start()
    }

    private fun showNotification(call: Call, label: String, number: String?, photo: Bitmap?, quiet: Boolean) {
        if (shownCall != call) {
            shownCall?.unregisterCallback(stateCallback)
            shownCall = call
            call.registerCallback(stateCallback)
        }
        shownLabel = label
        shownNumber = number
        shownPhoto = photo
        shownQuiet = quiet
        postNotification(call)
    }

    private fun refreshNotification() {
        val call = shownCall ?: return
        val st = call.stateCompat()
        if (st == Call.STATE_DISCONNECTED || st == Call.STATE_DISCONNECTING) return
        postNotification(call)
    }

    /**
     * Posted through startForeground: Android 14+ refuses a CallStyle
     * notification that is neither a foreground service's nor carrying a
     * full-screen intent (it used to be tolerated only while telecom still
     * saw the call, which is why quick hang-ups crashed). If the promotion
     * is refused for any reason, fall back to a plain notification.
     */
    private fun postNotification(call: Call) {
        val st = call.stateCompat()
        val incoming = st == Call.STATE_RINGING
        // Only a ringing call with the phone in use wants a heads-up; an
        // outgoing or connected call never does.
        val quiet = shownQuiet || !incoming
        val audio = callAudioState
        val sim = simLabel(call)
        try {
            val n = Notifications.buildCall(
                this, shownLabel, incoming, quiet, shownNumber, shownPhoto, sim,
                muted = audio?.isMuted == true,
                speaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
                bluetooth = audio?.route == CallAudioState.ROUTE_BLUETOOTH,
                stateLabel = stateName(st),
                connectedAt = if (st == Call.STATE_ACTIVE) call.details.connectTimeMillis else 0L
            )
            startForeground(Notifications.ID_CALL, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (_: Exception) {
            Notifications.postPlainCall(this, shownLabel, incoming, quiet, shownNumber, shownPhoto, sim)
        }
    }

    private fun clearNotification() {
        shownCall?.unregisterCallback(stateCallback)
        shownCall = null
        shownPhoto = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        Notifications.cancelCall(this)
    }

    /** "SIM 1 · Singtel" for the notification, or null on single-SIM phones. */
    private fun simLabel(call: Call): String? {
        if (!SimUtil.isDual(this)) return null
        val sim = SimUtil.byHandle(this, call.details.accountHandle) ?: return null
        return "SIM ${sim.slot + 1} · ${sim.name}"
    }
}
