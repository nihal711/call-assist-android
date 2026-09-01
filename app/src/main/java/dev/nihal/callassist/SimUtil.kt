package dev.nihal.callassist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

object SimUtil {

    /**
     * One call-capable SIM. [slot] is 0-based; the badge shows slot + 1.
     * [name] is what the user calls the SIM in the phone's SIM manager
     * (falls back to the carrier), [color] its SIM-manager tint.
     */
    data class Sim(
        val handle: PhoneAccountHandle,
        val slot: Int,
        val name: String,
        val number: String?,
        val color: Int
    ) {
        val badgeText: String get() = "${slot + 1}"
    }

    // Used when the subscription carries no tint of its own.
    private val FALLBACK_COLORS = intArrayOf(0xFF2E6FE8.toInt(), 0xFFE8873A.toInt(), 0xFF26A69A.toInt())

    @Volatile
    private var cache: List<Sim>? = null

    /** Re-reads the SIM list from telecom. Cheap; call from onResume. */
    fun refresh(ctx: Context): List<Sim> = load(ctx).also { cache = it }

    fun sims(ctx: Context): List<Sim> = cache ?: refresh(ctx)

    /** True when there is a real choice to make. All SIM UI keys off this. */
    fun isDual(ctx: Context): Boolean = sims(ctx).size > 1

    fun byHandle(ctx: Context, h: PhoneAccountHandle?): Sim? =
        h?.let { handle -> sims(ctx).firstOrNull { it.handle == handle } }

    fun byId(ctx: Context, id: String): Sim? =
        if (id.isEmpty()) null else sims(ctx).firstOrNull { it.handle.id == id }

    /** Matches a call-log row's PHONE_ACCOUNT_COMPONENT_NAME / PHONE_ACCOUNT_ID. */
    fun byLogColumns(ctx: Context, component: String?, id: String?): Sim? {
        if (id.isNullOrEmpty()) return null
        return sims(ctx).firstOrNull {
            it.handle.id == id &&
                (component == null || it.handle.componentName.flattenToString() == component)
        }
    }

    /** The SIM Android itself would pick (Settings → SIM manager → Calls), if fixed. */
    fun systemDefault(ctx: Context): Sim? =
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            byHandle(ctx, tm.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL))
        } catch (_: Exception) {
            null
        }

    /**
     * The SIM outgoing calls will use under the current preference, or null
     * when the user is going to be asked (SIM_ASK, or SIM_SYSTEM with the
     * phone itself set to ask).
     */
    fun selected(ctx: Context): Sim? = when (Prefs.simMode(ctx)) {
        Prefs.SIM_FIXED -> byId(ctx, Prefs.simId(ctx)) ?: systemDefault(ctx)
        Prefs.SIM_ASK -> null
        else -> systemDefault(ctx)
    }

    /** Short text for the preference as it stands, e.g. "Singtel", "Ask every time". */
    fun modeLabel(ctx: Context): String = when (Prefs.simMode(ctx)) {
        Prefs.SIM_ASK -> "Ask every time"
        Prefs.SIM_FIXED -> byId(ctx, Prefs.simId(ctx))?.name ?: "System default"
        else -> systemDefault(ctx)?.let { "System default (${it.name})" } ?: "System default"
    }

    private fun load(ctx: Context): List<Sim> {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        val handles = try {
            tm.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            return emptyList()
        }
        val subs = subscriptions(ctx)
        val telephony = ctx.getSystemService(TelephonyManager::class.java)
        val out = ArrayList<Sim>()
        for ((i, h) in handles.withIndex()) {
            val acct = try {
                tm.getPhoneAccount(h)
            } catch (_: Exception) {
                null
            }
            // Only SIM-backed accounts get a badge; a VoIP account registered by
            // another app would otherwise show up as a third "SIM".
            if (acct != null && acct.capabilities and PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION == 0) continue

            val sub = subFor(telephony, subs, h, i)
            val slot = sub?.slot ?: i
            val label = acct?.label?.toString()?.takeIf { it.isNotBlank() }
            val name = sub?.name ?: label ?: "SIM ${slot + 1}"
            val number = sub?.number?.takeIf { it.isNotBlank() }
                ?: acct?.address?.schemeSpecificPart?.takeIf { it.isNotBlank() }
            val color = sub?.color?.takeIf { it != 0 }
                ?: acct?.highlightColor?.takeIf { it != 0 }
                ?: FALLBACK_COLORS[slot % FALLBACK_COLORS.size]
            out.add(Sim(h, slot, name, number, color))
        }
        return out.sortedBy { it.slot }
    }

    private class SubInfo(val id: Int, val slot: Int, val name: String?, val number: String?, val color: Int)

    private fun subscriptions(ctx: Context): List<SubInfo> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            (sm.activeSubscriptionInfoList ?: emptyList()).map { s ->
                val number = try {
                    @Suppress("DEPRECATION") s.number
                } catch (_: Exception) {
                    null
                }
                SubInfo(
                    s.subscriptionId, s.simSlotIndex,
                    s.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: s.carrierName?.toString()?.takeIf { it.isNotBlank() },
                    number, s.iconTint
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Ties a telecom account to its subscription. Android 11+ can ask
     * telephony directly; before that the account id is the subscription id
     * or ICCID, so fall back to position.
     */
    private fun subFor(
        tm: TelephonyManager,
        subs: List<SubInfo>,
        h: PhoneAccountHandle,
        index: Int
    ): SubInfo? {
        if (subs.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 30) {
            val subId = try {
                tm.getSubscriptionId(h)
            } catch (_: Exception) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            subs.firstOrNull { it.id == subId }?.let { return it }
        }
        h.id.toIntOrNull()?.let { id -> subs.firstOrNull { it.id == id }?.let { return it } }
        return subs.sortedBy { it.slot }.getOrNull(index)
    }

    // ---------------- Shared UI pieces ----------------

    /** Small coloured square carrying the slot number; the SIM's identity everywhere in the app. */
    fun badge(ctx: Context, sim: Sim, sizeDp: Int = 18): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            bind(this, sim)
        }

    fun bind(badge: TextView, sim: Sim) {
        badge.text = sim.badgeText
        badge.gravity = Gravity.CENTER
        badge.includeFontPadding = false
        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.setTypeface(badge.typeface, Typeface.BOLD)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        badge.setBackgroundResource(R.drawable.bg_sim_badge)
        badge.backgroundTintList = ColorStateList.valueOf(sim.color)
    }

    /** "Singtel" over a smaller "+65 9123 4567" — the row body for SIM pickers. */
    fun twoLine(ctx: Context, sim: Sim): CharSequence =
        Ui.twoLine(ctx, sim.name, sim.number?.let { pretty(it) } ?: "SIM ${sim.slot + 1}")

    fun pretty(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

    /**
     * "Call with" sheet that sets the app-wide preference. Rows: each SIM,
     * then "Ask every time", then the phone's own setting.
     */
    fun showPicker(activity: Activity, onDone: () -> Unit) {
        val sims = sims(activity)
        val labels = ArrayList<CharSequence>()
        val icons = ArrayList<View?>()
        for (s in sims) {
            labels.add(twoLine(activity, s))
            icons.add(badge(activity, s, 22))
        }
        labels.add(Ui.twoLine(activity, "Ask every time", "Choose a SIM as each call is placed"))
        icons.add(null)
        val sys = systemDefault(activity)
        labels.add(
            Ui.twoLine(
                activity, "Phone settings default",
                sys?.let { "Currently ${it.name}" } ?: "Follows Settings → SIM manager"
            )
        )
        icons.add(null)

        val current = when (Prefs.simMode(activity)) {
            Prefs.SIM_FIXED -> sims.indexOfFirst { it.handle.id == Prefs.simId(activity) }
                .takeIf { it >= 0 } ?: sims.size + 1
            Prefs.SIM_ASK -> sims.size
            else -> sims.size + 1
        }
        Sheet(activity)
            .title("Call with")
            .singleChoice(labels, current, icons) { which ->
                when {
                    which < sims.size -> {
                        val s = sims[which]
                        Prefs.setSim(activity, Prefs.SIM_FIXED, s.handle.id, s.name)
                    }
                    which == sims.size -> Prefs.setSim(activity, Prefs.SIM_ASK, "", "Ask every time")
                    else -> Prefs.setSim(activity, Prefs.SIM_SYSTEM, "", "System default")
                }
                onDone()
            }
            .negative()
            .show()
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
