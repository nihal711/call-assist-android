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

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun set(c: Call, lbl: String, photo: Uri?) {
        call = c
        label = lbl
        photoUri = photo
        listeners.forEach { it() }
    }

    fun clear(c: Call) {
        if (call == c) {
            call = null
            photoUri = null
            listeners.forEach { it() }
        }
    }

    /** Call state or audio route changed; listeners re-render from [call]. */
    fun notifyChanged() = listeners.forEach { it() }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
}
