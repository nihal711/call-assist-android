package dev.nihal.callassist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils

/**
 * Automation for the intercom contact:
 *   answer -> save mute state, mute mic -> ~1.2s settle ->
 *   send gate code, digits 1s apart -> wait 5s ->
 *   retry up to 3 attempts total -> hang up ~3.5s after the last wait ->
 *   40s failsafe hangup no matter what.
 * On disconnect: restore the previous mute state and resume any call that was
 * put on hold when the intercom barged in. If WE had to hang up (intercom never
 * disconnected), warn that the gate may not have opened — when the code works,
 * the intercom normally ends the call itself.
 * Muting doesn't affect DTMF — tones are injected by the modem, not the mic.
 */
object AutomationEngine {
    private val handler = Handler(Looper.getMainLooper())

    private const val FIRST_TONE_DELAY_MS = 1200L   // let the audio path settle after answering
    private const val DIGIT_GAP_MS = 1000L          // digit starts 1s apart (~750ms silence between tones)
    private const val TONE_MS = 250L                // how long each DTMF tone is held
    private const val RETRY_WAIT_MS = 5000L         // wait after each attempt before checking
    private const val MAX_ATTEMPTS = 3
    private const val HANGUP_AFTER_LAST_MS = 3500L
    private const val FAILSAFE_HANGUP_MS = 40000L

    /** Cheap check usable on the main thread — no contacts query. */
    fun numberMatches(ctx: Context, number: String?): Boolean {
        if (!Prefs.enabled(ctx)) return false
        val target = Prefs.contactName(ctx).trim()
        if (target.isEmpty() || number.isNullOrBlank()) return false
        val digits = target.filter { it.isDigit() }
        return digits.length >= 5 && PhoneNumberUtils.compare(number, target)
    }

    fun nameMatches(ctx: Context, contactName: String?): Boolean {
        if (!Prefs.enabled(ctx)) return false
        val target = Prefs.contactName(ctx).trim()
        return target.isNotEmpty() && contactName != null &&
            contactName.trim().equals(target, ignoreCase = true)
    }

    fun start(ctx: Context, call: Call, label: String) {
        val appCtx = ctx.applicationContext
        Prefs.log(appCtx, "Incoming call from \"$label\" — auto-answering")
        Notifications.automation(appCtx, "Answering $label — entering code…")

        val token = Any()
        var sequenceStarted = false
        var wasMuted = false
        var weHungUp = false
        val startedAt = android.os.SystemClock.elapsedRealtime()

        // A call that was active (or already held) before the intercom barged in.
        // Telecom holds it when we answer; we resume it when the intercom is done.
        val heldCall = CallService.instance?.calls?.firstOrNull {
            it != call && (it.stateCompat() == Call.STATE_ACTIVE || it.stateCompat() == Call.STATE_HOLDING)
        }
        if (heldCall != null) Prefs.log(appCtx, "Another call is in progress — it will be held and resumed after")

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                when (state) {
                    Call.STATE_ACTIVE -> if (!sequenceStarted) {
                        sequenceStarted = true
                        wasMuted = CallService.instance?.callAudioState?.isMuted ?: false
                        try {
                            CallService.instance?.setMuted(true)
                            Prefs.log(appCtx, "Call active — mic muted, sending code shortly")
                        } catch (e: Exception) {
                            Prefs.log(appCtx, "Call active — could not mute mic (${e.message})")
                        }
                        handler.postDelayed(
                            { attempt(appCtx, c, token, 1) { weHungUp = true } },
                            token, FIRST_TONE_DELAY_MS
                        )
                    }
                    Call.STATE_DISCONNECTED -> {
                        handler.removeCallbacksAndMessages(token)
                        c.unregisterCallback(this)
                        try {
                            CallService.instance?.setMuted(wasMuted)
                        } catch (_: Exception) {
                        }
                        if (heldCall != null && heldCall.stateCompat() == Call.STATE_HOLDING) {
                            try {
                                heldCall.unhold()
                                Prefs.log(appCtx, "Resumed the held call")
                            } catch (_: Exception) {
                            }
                        }
                        val secs = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
                        if (weHungUp) {
                            Prefs.log(appCtx, "We hung up after ${secs}s — gate may NOT have opened")
                            Notifications.automation(
                                appCtx,
                                "⚠ Entered the code ${MAX_ATTEMPTS}× for $label but it never hung up — the gate may not have opened"
                            )
                        } else {
                            Prefs.log(appCtx, "\"$label\" hung up after ${secs}s — done")
                            Notifications.automation(appCtx, "Opened — code entered for $label")
                        }
                    }
                }
            }
        }
        call.registerCallback(callback)

        if (call.stateCompat() == Call.STATE_ACTIVE) {
            callback.onStateChanged(call, Call.STATE_ACTIVE)
        } else {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        handler.postDelayed({
            if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                weHungUp = true
                Prefs.log(appCtx, "Failsafe: hanging up after ${FAILSAFE_HANGUP_MS / 1000}s")
                call.disconnect()
            }
        }, token, FAILSAFE_HANGUP_MS)
    }

    private fun attempt(ctx: Context, call: Call, token: Any, n: Int, markWeHungUp: () -> Unit) {
        val code = Prefs.gateCode(ctx).trim().ifEmpty { Prefs.DEFAULT_CODE }
        Prefs.log(ctx, "Attempt $n/$MAX_ATTEMPTS — sending \"$code\"")
        var t = 0L
        for (ch in code) {
            scheduleTone(call, token, ch, t)
            t += DIGIT_GAP_MS
        }
        val seqEnd = t - DIGIT_GAP_MS + TONE_MS
        // Log once the last tone has actually been played, then again when the
        // listen-for-hangup window is up — so the log shows the waiting, not
        // just the sending.
        handler.postDelayed({
            if (call.stateCompat() == Call.STATE_DISCONNECTED) return@postDelayed
            Prefs.log(
                ctx,
                "Code sent (${code.length} tones) — waiting ${RETRY_WAIT_MS / 1000}s for the gate"
            )
        }, token, seqEnd)
        handler.postDelayed({
            if (call.stateCompat() == Call.STATE_DISCONNECTED) return@postDelayed
            if (n < MAX_ATTEMPTS) {
                Prefs.log(ctx, "Still connected ${RETRY_WAIT_MS / 1000}s after attempt $n — retrying")
                attempt(ctx, call, token, n + 1, markWeHungUp)
            } else {
                Prefs.log(
                    ctx,
                    "No response after $MAX_ATTEMPTS attempts — hanging up in ${HANGUP_AFTER_LAST_MS / 1000}s"
                )
                handler.postDelayed({
                    if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                        markWeHungUp()
                        call.disconnect()
                    }
                }, token, HANGUP_AFTER_LAST_MS)
            }
        }, token, seqEnd + RETRY_WAIT_MS)
    }

    private fun scheduleTone(call: Call, token: Any, ch: Char, at: Long) {
        handler.postDelayed({ call.playDtmfTone(ch) }, token, at)
        handler.postDelayed({ call.stopDtmfTone() }, token, at + TONE_MS)
    }
}
