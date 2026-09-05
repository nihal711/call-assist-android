package dev.nihal.callassist

import android.net.Uri
import android.telecom.Call
import java.util.concurrent.CopyOnWriteArraySet

object OngoingCall {
    var call: Call? = null
        private set
    var label: String = ""
        private set
    var photoUri: Uri? = null
        private set
    var isContact: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /**
     * Fired when the system silences the ringer (power key on Samsung phones, a
     * "silence" from the shade, a headset). The incoming screen turns this
     * into a decline when it is the one showing the ring.
     */
    private val silenceListeners = CopyOnWriteArraySet<() -> Unit>()
    fun notifySilenced() = silenceListeners.forEach { it() }
    fun addSilenceListener(l: () -> Unit) = silenceListeners.add(l)
    fun removeSilenceListener(l: () -> Unit) = silenceListeners.remove(l)

    fun set(c: Call, lbl: String, photo: Uri?, knownContact: Boolean = false) {
        call = c
        label = lbl
        photoUri = photo
        isContact = knownContact
        listeners.forEach { it() }
    }

    fun clear(c: Call) {
        if (call == c) {
            call = null
            photoUri = null
            isContact = false
            listeners.forEach { it() }
        }
    }

    /** Call state or audio route changed; listeners re-render from [call]. */
    fun notifyChanged() = listeners.forEach { it() }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}
