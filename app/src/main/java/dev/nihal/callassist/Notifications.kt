package dev.nihal.callassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build

object Notifications {
    private const val CH_INCOMING = "incoming_calls_v2"
    private const val CH_GATE = "gate_events"
    const val ID_CALL = 1
    const val ID_AUTOMATION = 2

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
        // Old channel had the default notification sound; settings are frozen per id.
        nm.deleteNotificationChannel("incoming_calls")
        // Own channel so its sound/vibration can be customized independently of calls.
        nm.createNotificationChannel(
            NotificationChannel(CH_GATE, "Gate opened", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when the intercom gate code is auto-executed"
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

    fun showCall(ctx: Context, label: String, incoming: Boolean) {
        ensureChannels(ctx)
        val pi = inCallPending(ctx)
        val b = Notification.Builder(ctx, CH_INCOMING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if (incoming) "Incoming call" else "Call in progress")
            .setContentText(label)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (incoming) b.setFullScreenIntent(pi, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CallStyle on purpose: the system treats a CallStyle notification
            // as the in-call UI's own and doesn't stack a heads-up banner over the
            // full-screen activity.
            val person = Person.Builder().setName(label).setImportant(true).build()
            val hangup = actionPending(ctx, CallActionReceiver.ACTION_HANGUP, 12)
            val style = if (incoming) {
                Notification.CallStyle.forIncomingCall(
                    person,
                    actionPending(ctx, CallActionReceiver.ACTION_DECLINE, 11),
                    actionPending(ctx, CallActionReceiver.ACTION_ANSWER, 10)
                )
            } else {
                Notification.CallStyle.forOngoingCall(person, hangup)
            }
            b.setStyle(style)
        }
        notifySafe(ctx, ID_CALL, b.build())
    }

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
        val n = Notification.Builder(ctx, CH_GATE)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
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
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
