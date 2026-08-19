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
 *   send gate code, digits 1s apart -> wait (Settings, default 5s) ->
 *   retry up to N attempts total (Settings, default 3) ->
 *   after the last attempt, wait an extra 5s buffer on top of the normal wait
 *   (so 5s wait -> the final attempt gets 10s), then hang up ->
 *   failsafe hangup sized to those settings.
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
    // Extra listening time after the final attempt's normal wait — some gates
    // act on the code slowly, so the last try gets wait + this before we give up.
    private const val HANGUP_AFTER_LAST_MS = 5000L

    /**
     * Last-resort hangup. Sized from the current settings plus headroom, so a
     * long wait × many attempts can't be guillotined mid-sequence — a fixed
     * 40s would have cut off e.g. 15s × 5.
     */
    private fun failsafeMs(ctx: Context, code: String): Long {
        val attempts = Prefs.maxAttempts(ctx)
        val perAttempt = code.length * DIGIT_GAP_MS + Prefs.retryWaitSeconds(ctx) * 1000L
        return FIRST_TONE_DELAY_MS + attempts * perAttempt + HANGUP_AFTER_LAST_MS + 10000L
    }

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
        Notifications.automation(appCtx, "Answering — entering code for $label")

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
                                "Attempted — code entered ${Prefs.maxAttempts(appCtx)}× but $label did not hang up, gate may not have opened."
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

        val failsafe = failsafeMs(appCtx, Prefs.gateCode(appCtx).trim().ifEmpty { Prefs.DEFAULT_CODE })
        handler.postDelayed({
            if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                weHungUp = true
                Prefs.log(appCtx, "Failsafe: hanging up after ${failsafe / 1000}s")
                call.disconnect()
            }
        }, token, failsafe)
    }

    private fun attempt(ctx: Context, call: Call, token: Any, n: Int, markWeHungUp: () -> Unit) {
        val code = Prefs.gateCode(ctx).trim().ifEmpty { Prefs.DEFAULT_CODE }
        val maxAttempts = Prefs.maxAttempts(ctx)
        val retryWaitMs = Prefs.retryWaitSeconds(ctx) * 1000L
        Prefs.log(ctx, "Attempt $n/$maxAttempts — sending \"$code\"")
        var t = 0L
        for (ch in code) {
            scheduleTone(call, token, ch, t)
            t += DIGIT_GAP_MS
        }
        val seqEnd = t - DIGIT_GAP_MS + TONE_MS
        // Log once the last tone has actually been played, then again when the
        // listen-for-hangup window is up — so the log shows the waiting, not
        // just the sending.
        var sentAt = 0L
        handler.postDelayed({
            if (call.stateCompat() == Call.STATE_DISCONNECTED) return@postDelayed
            sentAt = android.os.SystemClock.elapsedRealtime()
            Prefs.log(
                ctx,
                "Code sent (${code.length} tones) — waiting ${retryWaitMs / 1000}s for the gate"
            )
        }, token, seqEnd)
        handler.postDelayed({
            if (call.stateCompat() == Call.STATE_DISCONNECTED) return@postDelayed
            // Report what actually elapsed, not the configured wait — a doze or a
            // stalled main thread can turn a 7s window into 17s, and the log
            // claiming "7s" hid exactly that.
            val waited =
                if (sentAt == 0L) retryWaitMs / 1000
                else (android.os.SystemClock.elapsedRealtime() - sentAt + 500) / 1000
            if (n < maxAttempts) {
                Prefs.log(ctx, "Still connected ${waited}s after attempt $n — retrying")
                attempt(ctx, call, token, n + 1, markWeHungUp)
            } else {
                Prefs.log(
                    ctx,
                    "No response after $maxAttempts attempts — hanging up in ${HANGUP_AFTER_LAST_MS / 1000}s"
                )
                handler.postDelayed({
                    if (call.stateCompat() != Call.STATE_DISCONNECTED) {
                        markWeHungUp()
                        call.disconnect()
                    }
                }, token, HANGUP_AFTER_LAST_MS)
            }
        }, token, seqEnd + retryWaitMs)
    }

    private fun scheduleTone(call: Call, token: Any, ch: Char, at: Long) {
        handler.postDelayed({ call.playDtmfTone(ch) }, token, at)
        handler.postDelayed({ call.stopDtmfTone() }, token, at + TONE_MS)
    }
}
