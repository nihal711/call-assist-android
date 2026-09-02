package dev.nihal.callassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.CallAudioState
import android.telecom.VideoProfile

/** Handles the answer / decline / hang-up / mute / speaker buttons on the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = CallService.instance
        when (intent.action) {
            ACTION_ANSWER -> OngoingCall.call?.answer(VideoProfile.STATE_AUDIO_ONLY)
            ACTION_DECLINE -> OngoingCall.call?.reject(false, null)
            ACTION_HANGUP -> OngoingCall.call?.disconnect()
            // The audio-state callback re-posts the notification with the new labels.
            ACTION_MUTE -> svc?.setMuted(svc.callAudioState?.isMuted != true)
            ACTION_SPEAKER -> svc?.setAudioRoute(
                if (svc.callAudioState?.route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE
                else CallAudioState.ROUTE_SPEAKER
            )
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.nihal.callassist.ANSWER"
        const val ACTION_DECLINE = "dev.nihal.callassist.DECLINE"
        const val ACTION_HANGUP = "dev.nihal.callassist.HANGUP"
        const val ACTION_MUTE = "dev.nihal.callassist.MUTE"
        const val ACTION_SPEAKER = "dev.nihal.callassist.SPEAKER"
    }
}
