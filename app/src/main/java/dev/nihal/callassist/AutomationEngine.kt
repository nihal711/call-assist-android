package dev.nihal.callassist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils

/**
 * Automation for the intercom contact:
 *   answer -> mute mic -> ~1.2s settle -> send gate code (e.g. 6#) -> wait 5s ->
 *   if the intercom hasn't hung up, resend the code one digit per second ->
 *   hang up ~3.5s later. Failsafe hangup at 30s no matter what.
 * Muting doesn't affect DTMF — tones are injected by the modem, not the mic.
 */
object AutomationEngine {
    private val handler = Handler(Looper.getMainLooper())

    private const val FIRST_TONE_DELAY_MS = 1200L   // let the audio path settle after answering
    private const val FIRST_GAP_MS = 1000L          // digit starts 1s apart (~750ms silence between tones)
    private const val TONE_MS = 250L                // how long each DTMF tone is held
    private const val RETRY_WAIT_MS = 5000L         // wait after first attempt before checking
    private const val RETRY_GAP_MS = 1000L          // gap between digits on the retry
    private const val HANGUP_AFTER_RETRY_MS = 3500L
    private const val FAILSAFE_HANGUP_MS = 30000L

    fun shouldHandle(ctx: Context, contactName: String?, number: String?): Boolean {
        if (!Prefs.enabled(ctx)) return false
        val target = Prefs.contactName(ctx).trim()
        if (target.isEmpty()) return false
        if (contactName != null && contactName.trim().equals(target, ignoreCase = true)) return true
        // Also allow the setting to be a phone number instead of a contact name.
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 5 && number != null && PhoneNumberUtils.compare(number, target)) return true
        return false
    }

    fun start(ctx: Context, call: Call, label: String) {
        val appCtx = ctx.applicationContext
        Prefs.log(appCtx, "Incoming call from \"$label\" — auto-answering")
        Notifications.automation(appCtx, "Answering \"$label\", sending gate code…")

        val token = Any()
        var sequenceStarted = false

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> if (!sequenceStarted) {
                        sequenceStarted = true
                        runSequence(appCtx, c, token)
                    }
                    Call.STATE_DISCONNECTED -> {
                        handler.removeCallbacksAndMessages(token)
                        c.unregisterCallback(this)
                        try {
                            CallService.instance?.setMuted(false)
                        } catch (_: Exception) {
                        }
                        Prefs.log(appCtx, "Call with \"$label\" ended — mic unmuted")
                        Notifications.automation(appCtx, "Done — gate code sent to \"$label\"")
                    }
                }
            }
        }
        call.registerCallback(callback)

        if (call.stateCompat() == Call.STATE_ACTIVE) {
            sequenceStarted = true
            runSequence(appCtx, call, token)
        } else {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        handler.postDelayed({
            if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                Prefs.log(appCtx, "Failsafe: hanging up after 30s")
                call.disconnect()
            }
        }, token, FAILSAFE_HANGUP_MS)
    }

    private fun runSequence(ctx: Context, call: Call, token: Any) {
        val code = Prefs.gateCode(ctx).trim().ifEmpty { Prefs.DEFAULT_CODE }
        try {
            CallService.instance?.setMuted(true)
            Prefs.log(ctx, "Call active — mic muted, sending \"$code\" after a moment")
        } catch (e: Exception) {
            Prefs.log(ctx, "Call active — could not mute mic (${e.message}), sending \"$code\"")
        }

        var t = FIRST_TONE_DELAY_MS
        for (ch in code) {
            scheduleTone(call, token, ch, t)
            t += FIRST_GAP_MS
        }
        val firstDone = t - FIRST_GAP_MS + TONE_MS

        handler.postDelayed({
            if (call.stateCompat() == Call.STATE_DISCONNECTED) return@postDelayed
            Prefs.log(ctx, "Still connected after 5s — sending \"$code\" again")
            var rt = 0L
            for (ch in code) {
                scheduleTone(call, token, ch, rt)
                rt += RETRY_GAP_MS
            }
            handler.postDelayed({
                if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                    Prefs.log(ctx, "Hanging up")
                    call.disconnect()
                }
            }, token, rt - RETRY_GAP_MS + HANGUP_AFTER_RETRY_MS)
        }, token, firstDone + RETRY_WAIT_MS)
    }

    private fun scheduleTone(call: Call, token: Any, ch: Char, at: Long) {
        handler.postDelayed({ call.playDtmfTone(ch) }, token, at)
        handler.postDelayed({ call.stopDtmfTone() }, token, at + TONE_MS)
    }
}
