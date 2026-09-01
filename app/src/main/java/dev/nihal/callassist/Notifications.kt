package dev.nihal.callassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.graphics.drawable.Icon
import android.os.Build

object Notifications {
    private const val CH_INCOMING = "incoming_calls_v2"
    // Used while our own InCallActivity is on screen: low importance so the
    // system never stacks a heads-up banner over the full-screen call UI.
    private const val CH_QUIET = "incoming_calls_quiet"
    private const val CH_GATE = "gate_events"
    // Twin of CH_GATE with no sound. The gate event fires mid-call, and Android
    // plays an in-call notification beep for any channel that has a sound even
    // when the ringer is on vibrate/silent — so we pick the channel by ringer mode.
    private const val CH_GATE_QUIET = "gate_events_quiet"
    private const val CH_MISSED = "missed_calls"
    const val ID_CALL = 1
    const val ID_AUTOMATION = 2
    const val ID_MISSED = 4

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // Silent on purpose: ringing (sound / vibrate / mute) is done by the
            // system ringer, which already follows the phone's ringer mode. HIGH
            // importance is still required for the full-screen intent to fire.
            NotificationChannel(CH_INCOMING, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing calls"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Calls (call screen shown)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Call notification while the full-screen call UI is already showing"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        )
        // Old channel had the default notification sound; settings are frozen per id.
        nm.deleteNotificationChannel("incoming_calls")
        // Own channel so its sound/vibration can be customized independently of calls.
        nm.createNotificationChannel(
            NotificationChannel(CH_GATE, "Gate opened", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when the intercom gate code is auto-executed"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GATE_QUIET, "Gate opened (ringer off)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Same as Gate opened, used while the phone is on vibrate or mute"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            // Silent: the ring already happened; this is just the record of it.
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Calls that rang but weren't answered"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.deleteNotificationChannel("automation_status")
    }

    private fun inCallPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionPending(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * The call notification. Android 12+ gets CallStyle — Answer / Decline
     * while ringing, Hang up plus our Mute and Speaker actions once connected.
     * Android 14+ only accepts CallStyle from a foreground service or with a
     * full-screen intent, so [CallService] posts this through startForeground
     * (type phoneCall); [postPlainCall] is the fallback if that is refused.
     *
     * @param quiet low-importance channel (no heads-up, no full-screen intent)
     *   — used while our own InCallActivity is on screen and for every
     *   outgoing / connected call.
     */
    fun buildCall(
        ctx: Context,
        label: String,
        incoming: Boolean,
        quiet: Boolean,
        number: String?,
        photo: Bitmap?,
        sim: String?,
        muted: Boolean,
        speaker: Boolean
    ): Notification {
        val icon = photo?.let { Icon.createWithBitmap(it) }
        val b = baseCall(ctx, label, incoming, quiet, number, icon, sim)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CallStyle on purpose: the system treats a CallStyle notification
            // as the in-call UI's own and doesn't stack a heads-up banner over the
            // full-screen activity.
            val person = Person.Builder().setName(label).setIcon(icon).setImportant(true).build()
            val style = if (incoming) {
                Notification.CallStyle.forIncomingCall(
                    person,
                    actionPending(ctx, CallActionReceiver.ACTION_DECLINE, 11),
                    actionPending(ctx, CallActionReceiver.ACTION_ANSWER, 10)
                )
            } else {
                Notification.CallStyle.forOngoingCall(person, actionPending(ctx, CallActionReceiver.ACTION_HANGUP, 12))
            }
            b.setStyle(style)
            if (!incoming) {
                // CallStyle shows these alongside its own Hang up (three buttons max).
                b.addAction(action(ctx, if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, if (muted) "Unmute" else "Mute", CallActionReceiver.ACTION_MUTE, 13))
                b.addAction(action(ctx, R.drawable.ic_speaker, if (speaker) "Earpiece" else "Speaker", CallActionReceiver.ACTION_SPEAKER, 14))
            }
        }
        return b.build()
    }

    /** Style-less twin of [buildCall], posted with a plain notify(). */
    fun postPlainCall(
        ctx: Context,
        label: String,
        incoming: Boolean,
        quiet: Boolean,
        number: String?,
        photo: Bitmap?,
        sim: String?
    ) {
        val icon = photo?.let { Icon.createWithBitmap(it) }
        notifySafe(ctx, ID_CALL, baseCall(ctx, label, incoming, quiet, number, icon, sim).build())
    }

    private fun baseCall(
        ctx: Context,
        label: String,
        incoming: Boolean,
        quiet: Boolean,
        number: String?,
        icon: Icon?,
        sim: String?
    ): Notification.Builder {
        ensureChannels(ctx)
        val pi = inCallPending(ctx)
        val base = if (number != null && number != label) number else if (incoming) "Incoming call" else "Call in progress"
        val subtitle = if (sim != null) "$base  ·  $sim" else base
        return Notification.Builder(ctx, if (quiet) CH_QUIET else CH_INCOMING)
            .setSmallIcon(R.drawable.ic_phone)
            .setColor(ctx.getColor(R.color.accent))
            .setContentTitle(label)
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .also { b ->
                if (incoming && !quiet) b.setFullScreenIntent(pi, true)
                if (icon != null) b.setLargeIcon(icon)
            }
    }

    private fun action(ctx: Context, icon: Int, title: String, act: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, actionPending(ctx, act, code)).build()

    /** One per number (tagged); cleared when Recents opens. */
    fun missedCall(ctx: Context, label: String, number: String?, photo: Bitmap?) {
        ensureChannels(ctx)
        val openRecents = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_RECENTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setColor(ctx.getColor(R.color.red))
            .setContentTitle("Missed call")
            .setContentText(label)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openRecents)
        photo?.let { b.setLargeIcon(Icon.createWithBitmap(it)) }
        if (number != null) {
            // Distinct request codes per number, or UPDATE_CURRENT would make
            // every missed call's buttons act on the most recent number.
            val callBack = PendingIntent.getBroadcast(
                ctx, number.hashCode(),
                Intent(ctx, CallActionReceiver::class.java)
                    .setAction(CallActionReceiver.ACTION_CALL_BACK)
                    .putExtra(CallActionReceiver.EXTRA_NUMBER, number),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val sms = PendingIntent.getActivity(
                ctx, number.hashCode() + 1,
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(action2(ctx, R.drawable.ic_phone, "Call back", callBack))
            b.addAction(action2(ctx, R.drawable.ic_message, "Message", sms))
        }
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(number ?: label, ID_MISSED, b.build())
        } catch (_: Exception) {
        }
    }

    fun clearMissed(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try {
            nm.activeNotifications.filter { it.id == ID_MISSED }.forEach { nm.cancel(it.tag, ID_MISSED) }
        } catch (_: Exception) {
        }
    }

    private fun action2(ctx: Context, icon: Int, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()

    /** Opens the app straight to the Gate tab, where the event log lives. */
    private fun gateTabPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_GATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun automation(ctx: Context, text: String) {
        if (!Prefs.notifyGate(ctx)) return
        ensureChannels(ctx)
        val ringerOn = ctx.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL
        val n = Notification.Builder(ctx, if (ringerOn) CH_GATE else CH_GATE_QUIET)
            .setSmallIcon(R.drawable.ic_phone)
            .setColor(ctx.getColor(R.color.green))
            .setContentTitle("Call Assist")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(gateTabPending(ctx))
            .setAutoCancel(true)
            .build()
        notifySafe(ctx, ID_AUTOMATION, n)
    }

    fun roleLost(ctx: Context) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CH_GATE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Gate automation is OFF")
            .setContentText("Call Assist is no longer the default phone app. Tap to fix.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notifySafe(ctx, 3, n)
    }

    fun cancelCall(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_CALL)
    }

    private fun notifySafe(ctx: Context, id: Int, n: Notification) {
        // A rejected notification must never take the InCallService down with it.
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: Exception) {
        }
    }
}
