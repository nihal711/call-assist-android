package dev.nihal.callassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.VideoProfile

/** Handles the answer / decline / hang-up buttons on the CallStyle notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = OngoingCall.call ?: return
        when (intent.action) {
            ACTION_ANSWER -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            ACTION_DECLINE -> call.reject(false, null)
            ACTION_HANGUP -> call.disconnect()
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.nihal.callassist.ANSWER"
        const val ACTION_DECLINE = "dev.nihal.callassist.DECLINE"
        const val ACTION_HANGUP = "dev.nihal.callassist.HANGUP"
    }
}
