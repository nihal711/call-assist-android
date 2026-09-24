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
import org.json.JSONObject

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
    const val EXTRA_MISSED_TAG = "dev.nihal.callassist.MISSED_TAG"
    @Volatile private var channelsReady = false

    fun ensureChannels(ctx: Context) {
        if (channelsReady) return
        synchronized(this) {
            if (channelsReady) return
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
            channelsReady = true
        }
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
     * The call notification.
     *
     * Ringing: CallStyle on Android 12+ — the system's own Answer / Decline
     * treatment. Android 14+ only accepts CallStyle from a foreground service
     * or with a full-screen intent, so [CallService] posts this through
     * startForeground (type phoneCall); [postPlainCall] is the fallback.
     *
     * Ongoing: a custom layout instead of CallStyle, because Samsung's
     * shade hides CallStyle's buttons until the notification is expanded. This
     * keeps the round Mute / Speaker / Hang up controls visible even when
     * collapsed, with a Chronometer ticking live without re-posts.
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
        speaker: Boolean,
        bluetooth: Boolean = false,
        stateLabel: String = "",
        connectedAt: Long = 0L
    ): Notification {
        val icon = photo?.let { Icon.createWithBitmap(it) }
        // The custom ongoing layout carries its own photo; a largeIcon too would
        // double it and cost the whole right-hand column of the template.
        val b = baseCall(ctx, label, incoming, quiet, number, if (incoming) icon else null, sim)
        if (incoming) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val person = Person.Builder().setName(label).setIcon(icon).setImportant(true).build()
                b.setStyle(
                    Notification.CallStyle.forIncomingCall(
                        person,
                        actionPending(ctx, CallActionReceiver.ACTION_DECLINE, 11),
                        actionPending(ctx, CallActionReceiver.ACTION_ANSWER, 10)
                    )
                )
            }
            // Collapsed row with its own Decline / Answer: under Do not disturb
            // there is no heads-up, and Samsung's collapsed CallStyle hides the
            // buttons until expanded. Expanded + heads-up stay CallStyle.
            b.setCustomContentView(incomingViews(ctx, label, number, sim, photo))
            return b.build()
        }
        b.setStyle(Notification.DecoratedCustomViewStyle())
        b.setCustomContentView(
            callViews(ctx, R.layout.notification_call, label, number, sim, photo, muted, speaker, bluetooth, stateLabel, connectedAt)
        )
        b.setCustomBigContentView(
            callViews(ctx, R.layout.notification_call_big, label, number, sim, photo, muted, speaker, bluetooth, stateLabel, connectedAt)
        )
        return b.build()
    }

    private fun incomingViews(
        ctx: Context,
        label: String,
        number: String?,
        sim: String?,
        photo: Bitmap?
    ): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(ctx.packageName, R.layout.notification_call_incoming)
        if (photo != null) {
            rv.setImageViewBitmap(R.id.notifPhoto, photo)
        } else {
            rv.setImageViewResource(R.id.notifPhoto, R.drawable.ic_person)
            rv.setInt(R.id.notifPhoto, "setColorFilter", ctx.getColor(R.color.textSecondary))
            val pad = (8 * ctx.resources.displayMetrics.density).toInt()
            rv.setViewPadding(R.id.notifPhoto, pad, pad, pad, pad)
        }
        rv.setTextViewText(R.id.notifName, label)
        val parts = mutableListOf("Incoming call")
        if (number != null && number != label) parts.add(number)
        sim?.let { parts.add(it) }
        rv.setTextViewText(R.id.notifStatus, parts.joinToString("  ·  "))
        rv.setOnClickPendingIntent(R.id.notifDecline, actionPending(ctx, CallActionReceiver.ACTION_DECLINE, 11))
        rv.setOnClickPendingIntent(R.id.notifAnswer, actionPending(ctx, CallActionReceiver.ACTION_ANSWER, 10))
        return rv
    }

    private fun callViews(
        ctx: Context,
        layout: Int,
        label: String,
        number: String?,
        sim: String?,
        photo: Bitmap?,
        muted: Boolean,
        speaker: Boolean,
        bluetooth: Boolean,
        stateLabel: String,
        connectedAt: Long
    ): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(ctx.packageName, layout)
        if (photo != null) {
            rv.setImageViewBitmap(R.id.notifPhoto, photo)
        } else {
            rv.setImageViewResource(R.id.notifPhoto, R.drawable.ic_person)
            rv.setInt(R.id.notifPhoto, "setColorFilter", ctx.getColor(R.color.textSecondary))
            val pad = (8 * ctx.resources.displayMetrics.density).toInt()
            rv.setViewPadding(R.id.notifPhoto, pad, pad, pad, pad)
        }
        rv.setTextViewText(R.id.notifName, label)
        val parts = mutableListOf<String>()
        if (connectedAt <= 0L && stateLabel.isNotEmpty()) parts.add(stateLabel)
        if (layout == R.layout.notification_call_big && number != null && number != label) parts.add(parts.size, number)
        sim?.let { parts.add(it) }
        if (connectedAt > 0L) {
            rv.setViewVisibility(R.id.notifTimer, android.view.View.VISIBLE)
            rv.setChronometer(
                R.id.notifTimer,
                android.os.SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connectedAt),
                null, true
            )
            rv.setTextViewText(
                R.id.notifStatus,
                if (parts.isEmpty()) "" else "  ·  " + parts.joinToString("  ·  ")
            )
        } else {
            rv.setViewVisibility(R.id.notifTimer, android.view.View.GONE)
            rv.setTextViewText(R.id.notifStatus, parts.joinToString("  ·  "))
        }
        val primary = ctx.getColor(R.color.textPrimary)
        rv.setImageViewResource(R.id.notifMute, if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        rv.setInt(R.id.notifMute, "setBackgroundResource", if (muted) R.drawable.bg_notif_btn_red else R.drawable.bg_notif_btn)
        rv.setInt(R.id.notifMute, "setColorFilter", if (muted) 0xFFFFFFFF.toInt() else primary)
        val routeOn = speaker || bluetooth
        rv.setImageViewResource(R.id.notifSpeaker, if (bluetooth) R.drawable.ic_bluetooth else R.drawable.ic_speaker)
        rv.setInt(R.id.notifSpeaker, "setBackgroundResource", if (routeOn) R.drawable.bg_notif_btn_accent else R.drawable.bg_notif_btn)
        rv.setInt(R.id.notifSpeaker, "setColorFilter", if (routeOn) 0xFFFFFFFF.toInt() else primary)
        rv.setOnClickPendingIntent(R.id.notifMute, actionPending(ctx, CallActionReceiver.ACTION_MUTE, 13))
        rv.setOnClickPendingIntent(R.id.notifSpeaker, actionPending(ctx, CallActionReceiver.ACTION_SPEAKER, 14))
        rv.setOnClickPendingIntent(R.id.notifHangup, actionPending(ctx, CallActionReceiver.ACTION_HANGUP, 12))
        return rv
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

    /**
     * One per number (tagged); cleared when Recents opens.
     * Each is also recorded in [missedStore] so [restoreMissed] can bring back
     * the ones still unread after a reboot, as Telecom's own notification did.
     */
    fun missedCall(
        ctx: Context,
        label: String,
        number: String?,
        photo: Bitmap?,
        time: Long = System.currentTimeMillis()
    ) {
        ensureChannels(ctx)
        val tag = number ?: label
        // Per-tag request codes: the tag extra must not be overwritten by the
        // next missed call's (UPDATE_CURRENT).
        val openRecents = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_RECENTS)
                .putExtra(EXTRA_MISSED_TAG, tag)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissed = PendingIntent.getBroadcast(
            ctx, tag.hashCode(),
            Intent(ctx, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_MISSED_DISMISSED)
                .putExtra(EXTRA_MISSED_TAG, tag),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setColor(ctx.getColor(R.color.red))
            .setContentTitle("Missed call")
            .setContentText(label)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setWhen(time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openRecents)
            .setDeleteIntent(dismissed)
        photo?.let { b.setLargeIcon(Icon.createWithBitmap(it)) }
        if (number != null) {
            // Distinct request codes per number, or UPDATE_CURRENT would make
            // every missed call's buttons act on the most recent number.
            val callBack = PendingIntent.getActivity(
                ctx, number.hashCode(),
                Intent(ctx, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_CALL_BACK)
                    .putExtra(MainActivity.EXTRA_NUMBER, number)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
            ctx.getSystemService(NotificationManager::class.java).notify(tag, ID_MISSED, b.build())
            missedStore(ctx).edit()
                .putString(tag, JSONObject().put("label", label).put("number", number).put("time", time).toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun missedStore(ctx: Context) = ctx.getSharedPreferences("missed_calls", Context.MODE_PRIVATE)

    /** Tapped or swiped away: it shouldn't come back after a reboot. */
    fun forgetMissed(ctx: Context, tag: String) = missedStore(ctx).edit().remove(tag).apply()

    /** Re-posts the missed calls still unread when the phone shut down. Blocking — call off the main thread. */
    fun restoreMissed(ctx: Context) {
        for ((tag, raw) in missedStore(ctx).all) {
            try {
                val o = JSONObject(raw as String)
                val number = if (o.has("number")) o.getString("number") else null
                val info = ContactHelper.lookup(ctx, number)
                val photo = ContactHelper.loadPhoto(ctx, info.photoUri)
                missedCall(ctx, info.name ?: o.getString("label"), number, photo, o.getLong("time"))
            } catch (_: Exception) {
                forgetMissed(ctx, tag)
            }
        }
    }

    fun missedCount(ctx: Context): Int =
        try {
            ctx.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id == ID_MISSED }
        } catch (_: Exception) {
            0
        }

    fun clearMissed(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try {
            nm.activeNotifications.filter { it.id == ID_MISSED }.forEach { nm.cancel(it.tag, ID_MISSED) }
        } catch (_: Exception) {
        }
        missedStore(ctx).edit().clear().apply()
    }

    fun cancelMissed(ctx: Context, tag: String) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(tag, ID_MISSED)
        } catch (_: Exception) {
        }
        forgetMissed(ctx, tag)
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
        // Keyed off AutomationEngine's wording so the engine itself stays untouched:
        // "Answering…" keeps the handset, the "Opened" update swaps to an open lock.
        val icon = if (text.startsWith("Opened")) R.drawable.ic_lock_open else R.drawable.ic_phone
        val n = Notification.Builder(ctx, if (ringerOn) CH_GATE else CH_GATE_QUIET)
            .setSmallIcon(icon)
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
