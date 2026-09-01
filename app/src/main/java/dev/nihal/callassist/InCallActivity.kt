package dev.nihal.callassist

import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class InCallActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var boundCall: Call? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var muted = false
    private var speaker = false
    private var timerRunning = false
    private var photoLoaded = false
    private var keypadOpen = false

    private lateinit var bgPhoto: ImageView
    private lateinit var scrim: View
    private lateinit var avatarFrame: View
    private lateinit var avatar: TextView
    private lateinit var avatarPhoto: ImageView
    private lateinit var callerName: TextView
    private lateinit var callState: TextView
    private lateinit var simChip: View
    private lateinit var simChipBadge: TextView
    private lateinit var simChipName: TextView
    private lateinit var callTimer: Chronometer
    private lateinit var btnAddContact: View
    private lateinit var ringingButtons: View
    private lateinit var ringingSlide: View
    private lateinit var btnEndAnswer: View
    private lateinit var activeBar: View
    private lateinit var keypadPanel: View
    private lateinit var keypad: GridLayout
    private lateinit var dtmfDisplay: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnKeypad: ImageButton
    private lateinit var btnHold: ImageButton
    private lateinit var btnContact: ImageButton
    private lateinit var labelSpeaker: TextView
    private lateinit var labelHold: TextView
    private lateinit var labelContact: TextView
    private lateinit var slideAnswer: SeekBar

    // Second-call card
    private lateinit var otherCard: View
    private lateinit var otherAvatar: TextView
    private lateinit var otherName: TextView
    private lateinit var otherState: TextView
    private lateinit var btnSwap: ImageButton
    private lateinit var btnMerge: ImageButton

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = render()
        override fun onDetailsChanged(call: Call, details: Call.Details) = render()
    }

    // Full rebind (photo decode included) only when the call itself changes;
    // state/audio ticks just re-render the widgets.
    private val ongoingListener: () -> Unit = {
        runOnUiThread { if (OngoingCall.call != boundCall) bindCall() else render() }
    }

    // Power button while ringing: the screen turning off is our signal (apps
    // can't see the power key itself). FLAG_KEEP_SCREEN_ON below guarantees a
    // timeout can't fire this — only a deliberate press can.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val call = boundCall ?: return
            if (call.stateCompat() == Call.STATE_RINGING) {
                call.reject(false, null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incall)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        // Screen off against the ear — otherwise
        // FLAG_KEEP_SCREEN_ON leaves the whole call UI live under a cheek.
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "callassist:proximity")
        }

        bgPhoto = findViewById(R.id.bgPhoto)
        scrim = findViewById(R.id.scrim)
        avatarFrame = findViewById(R.id.avatarFrame)
        avatar = findViewById(R.id.avatar)
        avatarPhoto = findViewById(R.id.avatarPhoto)
        callerName = findViewById(R.id.callerName)
        callState = findViewById(R.id.callState)
        simChip = findViewById(R.id.simChip)
        simChipBadge = findViewById(R.id.simChipBadge)
        simChipName = findViewById(R.id.simChipName)
        callTimer = findViewById(R.id.callTimer)
        btnAddContact = findViewById(R.id.btnAddContact)
        ringingButtons = findViewById(R.id.ringingButtons)
        ringingSlide = findViewById(R.id.ringingSlide)
        btnEndAnswer = findViewById(R.id.btnEndAnswer)
        activeBar = findViewById(R.id.activeBar)
        keypadPanel = findViewById(R.id.keypadPanel)
        keypad = findViewById(R.id.keypad)
        dtmfDisplay = findViewById(R.id.dtmfDisplay)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnKeypad = findViewById(R.id.btnKeypad)
        btnHold = findViewById(R.id.btnHold)
        btnContact = findViewById(R.id.btnContact)
        labelSpeaker = findViewById(R.id.labelSpeaker)
        labelHold = findViewById(R.id.labelHold)
        labelContact = findViewById(R.id.labelContact)
        slideAnswer = findViewById(R.id.slideAnswer)
        otherCard = findViewById(R.id.otherCallCard)
        otherAvatar = findViewById(R.id.otherAvatar)
        otherName = findViewById(R.id.otherName)
        otherState = findViewById(R.id.otherState)
        btnSwap = findViewById(R.id.btnSwap)
        btnMerge = findViewById(R.id.btnMerge)

        avatarPhoto.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatarPhoto.clipToOutline = true

        findViewById<View>(R.id.btnAnswer).setOnClickListener { haptic(it); answer() }
        findViewById<View>(R.id.btnDecline).setOnClickListener { haptic(it); boundCall?.reject(false, null) }
        findViewById<View>(R.id.btnMessageLocked).setOnClickListener { showQuickReplies() }
        findViewById<View>(R.id.btnMessageSlide).setOnClickListener { showQuickReplies() }
        findViewById<View>(R.id.btnHangup).setOnClickListener { haptic(it); boundCall?.disconnect() }
        btnEndAnswer.setOnClickListener {
            haptic(it)
            otherCall()?.disconnect()
            answer()
        }

        btnMute.setOnClickListener {
            muted = !muted
            CallService.instance?.setMuted(muted)
            renderAudioToggles()
        }
        btnSpeaker.setOnClickListener {
            val audio = CallService.instance?.callAudioState
            // With Bluetooth in the mix a blind toggle can't reach every route.
            if (audio != null && audio.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0) {
                showRouteSheet(audio)
            } else {
                speaker = audio?.route != CallAudioState.ROUTE_SPEAKER
                CallService.instance?.setAudioRoute(
                    if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
                )
                renderAudioToggles()
            }
        }
        btnKeypad.setOnClickListener { setKeypadOpen(!keypadOpen) }
        btnHold.setOnClickListener {
            val c = boundCall ?: return@setOnClickListener
            when (c.stateCompat()) {
                Call.STATE_ACTIVE -> if (c.details.can(Call.Details.CAPABILITY_HOLD)) c.hold()
                Call.STATE_HOLDING -> c.unhold()
                else -> {}
            }
        }
        findViewById<View>(R.id.btnAddCall).setOnClickListener {
            // The dialer keeps this call via the return-to-call bar; dialling
            // another number from there puts this one on hold.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        btnContact.setOnClickListener { openContact() }
        btnAddContact.setOnClickListener { openContact() }
        btnSwap.setOnClickListener { swapCalls() }
        btnMerge.setOnClickListener {
            val other = otherCall() ?: return@setOnClickListener
            try {
                boundCall?.conference(other)
            } catch (_: Exception) {
            }
        }

        slideAnswer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress >= 85) {
                    haptic(sb)
                    answer()
                } else {
                    ObjectAnimator.ofInt(sb, "progress", sb.progress, 0)
                        .setDuration(180).start()
                }
            }
        })

        buildKeypad()
        OngoingCall.addListener(ongoingListener)
        bindCall()
    }

    override fun onDestroy() {
        try {
            if (proximityLock?.isHeld == true) {
                proximityLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        OngoingCall.removeListener(ongoingListener)
        boundCall?.unregisterCallback(callback)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            boundCall?.stateCompat() == Call.STATE_RINGING
        ) {
            // Silence ring + vibration locally; the caller keeps hearing ringback.
            try {
                getSystemService(TelecomManager::class.java).silenceRinger()
            } catch (_: Exception) {
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun answer() {
        boundCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /** The one live call that isn't on screen: held behind this one, or active behind a waiting one. */
    private fun otherCall(): Call? =
        CallService.instance?.calls?.firstOrNull {
            it != boundCall &&
                !it.details.hasProperty(Call.Details.PROPERTY_CONFERENCE) &&
                it.stateCompat() != Call.STATE_DISCONNECTED && it.stateCompat() != Call.STATE_DISCONNECTING
        }

    /** Telecom holds the current call when the held one is resumed; the screen follows the resumed call. */
    private fun swapCalls() {
        val other = otherCall() ?: return
        try {
            if (other.stateCompat() == Call.STATE_HOLDING) other.unhold()
            else boundCall?.hold()
        } catch (_: Exception) {
        }
        CallService.instance?.promote(other)
    }

    private fun openContact() {
        val number = boundCall?.details?.handle?.schemeSpecificPart
        if (number.isNullOrBlank()) {
            Toast.makeText(this, "No number to save", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = ContactHelper.lookupContactUri(this, number)
            startActivity(
                if (uri != null) Intent(Intent.ACTION_VIEW, uri)
                else Intent(Intent.ACTION_INSERT_OR_EDIT)
                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open contacts app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRouteSheet(audio: CallAudioState) {
        val routes = listOf(
            CallAudioState.ROUTE_EARPIECE to "Phone",
            CallAudioState.ROUTE_WIRED_HEADSET to "Wired headset",
            CallAudioState.ROUTE_SPEAKER to "Speaker",
            CallAudioState.ROUTE_BLUETOOTH to "Bluetooth"
        ).filter { audio.supportedRouteMask and it.first != 0 }
        Sheet(this)
            .title("Audio output")
            .singleChoice(routes.map { it.second }, routes.indexOfFirst { it.first == audio.route }) { i ->
                CallService.instance?.setAudioRoute(routes[i].first)
            }
            .negative()
            .show()
    }

    /**
     * Held while the voice is in the earpiece and the call is live, so the
     * screen blanks against the ear; released (waiting for the sensor to
     * clear) on speaker/Bluetooth, while ringing, and when the call ends.
     */
    private fun updateProximity(state: Int) {
        val lock = proximityLock ?: return
        val route = CallService.instance?.callAudioState?.route
        val live = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING
        val onEar = route == null || route == CallAudioState.ROUTE_EARPIECE
        try {
            if (live && onEar) {
                if (!lock.isHeld) lock.acquire(60 * 60 * 1000L)
            } else if (lock.isHeld) {
                lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (_: Exception) {
        }
    }

    /** Muted reads as a warning — red with a crossed-out mic; speaker stays accent blue. */
    private fun renderAudioToggles() {
        btnMute.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        btnMute.contentDescription = if (muted) "Unmute" else "Mute"
        btnMute.backgroundTintList = ColorStateList.valueOf(
            getColor(if (muted) R.color.red else R.color.card2)
        )
        btnMute.imageTintList = ColorStateList.valueOf(
            if (muted) 0xFFFFFFFF.toInt() else getColor(R.color.textPrimary)
        )
        val route = CallService.instance?.callAudioState?.route
        val bt = route == CallAudioState.ROUTE_BLUETOOTH
        speaker = route == CallAudioState.ROUTE_SPEAKER
        btnSpeaker.setImageResource(if (bt) R.drawable.ic_bluetooth else R.drawable.ic_speaker)
        labelSpeaker.text = if (bt) "Bluetooth" else "Speaker"
        btnSpeaker.contentDescription = labelSpeaker.text
        setToggle(btnSpeaker, speaker || bt)
    }

    private fun setToggle(btn: ImageButton, active: Boolean) {
        btn.backgroundTintList = ColorStateList.valueOf(
            getColor(if (active) R.color.accent else R.color.card2)
        )
        btn.imageTintList = ColorStateList.valueOf(
            if (active) 0xFFFFFFFF.toInt() else getColor(R.color.textPrimary)
        )
    }

    private fun showQuickReplies() {
        val replies = Prefs.quickReplies(this)
        Sheet(this)
            .title("Decline with message")
            .items(replies.toList()) { i ->
                try {
                    boundCall?.reject(true, replies[i])
                } catch (_: Exception) {
                    boundCall?.reject(false, null)
                }
            }
            .negative()
            .show()
    }

    private fun setKeypadOpen(open: Boolean) {
        keypadOpen = open
        keypadPanel.visibility = if (open) View.VISIBLE else View.GONE
        // The keypad needs the vertical room the avatar was using.
        avatarFrame.visibility = if (open) View.GONE else View.VISIBLE
        if (!open) dtmfDisplay.text = ""
        setToggle(btnKeypad, open)
    }

    /** Same cells as the dialler's keypad: big digit over its letters, ripple, tone + echo into the display. */
    private fun buildKeypad() {
        val ripple = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        val font = Typeface.create("sec-roboto-light", Typeface.NORMAL)
        val keys = listOf(
            '1' to "", '2' to "ABC", '3' to "DEF",
            '4' to "GHI", '5' to "JKL", '6' to "MNO",
            '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
            '*' to "", '0' to "+", '#' to ""
        )
        for ((digit, letters) in keys) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple.resourceId)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = dp(64) }
            }
            cell.addView(TextView(this).apply {
                text = digit.toString()
                textSize = 30f
                typeface = font
                setTextColor(getColor(R.color.textPrimary))
                gravity = Gravity.CENTER
            })
            if (letters.isNotEmpty()) cell.addView(TextView(this).apply {
                text = letters
                textSize = 10f
                typeface = font
                alpha = 0.6f
                setTextColor(getColor(R.color.textPrimary))
                gravity = Gravity.CENTER
            })
            cell.setOnClickListener {
                val c = boundCall ?: return@setOnClickListener
                if (Prefs.keyHaptics(this)) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dtmfDisplay.append(digit.toString())
                c.playDtmfTone(digit)
                handler.postDelayed({ c.stopDtmfTone() }, 250)
            }
            keypad.addView(cell)
        }
    }

    private fun bindCall() {
        boundCall?.unregisterCallback(callback)
        boundCall = OngoingCall.call
        boundCall?.registerCallback(callback)
        photoLoaded = false
        timerRunning = false
        callTimer.stop()
        callTimer.visibility = View.GONE
        if (keypadOpen) setKeypadOpen(false)
        loadPhoto()
        render()
    }

    private fun loadPhoto() {
        val uri = OngoingCall.photoUri
        if (uri == null) {
            bgPhoto.visibility = View.GONE
            scrim.visibility = View.GONE
            avatarPhoto.visibility = View.GONE
            avatar.visibility = View.VISIBLE
            return
        }
        if (photoLoaded) return
        try {
            avatarPhoto.setImageURI(uri)
            if (avatarPhoto.drawable != null) {
                photoLoaded = true
                avatarPhoto.visibility = View.VISIBLE
                avatar.visibility = View.INVISIBLE
                // iOS-style backdrop: blurred photo behind a dark gradient scrim.
                bgPhoto.setImageURI(uri)
                if (Build.VERSION.SDK_INT >= 31) {
                    bgPhoto.setRenderEffect(
                        RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP)
                    )
                }
                bgPhoto.visibility = View.VISIBLE
                scrim.visibility = View.VISIBLE
                // Over the photo + scrim the text must be light regardless of theme.
                callerName.setTextColor(0xFFFFFFFF.toInt())
                callState.setTextColor(0xD9FFFFFF.toInt())
                for (id in intArrayOf(
                    R.id.labelMute, R.id.labelKeypad, R.id.labelSpeaker,
                    R.id.labelAddCall, R.id.labelHold, R.id.labelContact
                )) findViewById<TextView>(id).setTextColor(0xD9FFFFFF.toInt())
            }
        } catch (_: Exception) {
        }
    }

    private fun renderOtherCall(state: Int) {
        val other = otherCall()
        if (other == null) {
            otherCard.visibility = View.GONE
            btnEndAnswer.visibility = View.GONE
            return
        }
        val label = CallService.instance?.labelFor(other)
            ?: other.details.handle?.schemeSpecificPart ?: "Unknown"
        otherCard.visibility = View.VISIBLE
        otherAvatar.text = Ui.initial(label)
        otherAvatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(label))
        otherName.text = label
        otherState.text = stateName(other.stateCompat())
        val ringing = state == Call.STATE_RINGING
        // Swapping only makes sense between two answered calls; while a call
        // waits behind a ringing one the choice is answer (holds), decline, or end & answer.
        btnSwap.visibility = if (ringing) View.GONE else View.VISIBLE
        btnMerge.visibility =
            if (!ringing && boundCall?.details?.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true) View.VISIBLE
            else View.GONE
        btnEndAnswer.visibility = if (ringing) View.VISIBLE else View.GONE
    }

    private fun render() {
        runOnUiThread {
            val call = boundCall
            if (call == null) {
                finish()
                return@runOnUiThread
            }
            callerName.text = OngoingCall.label
            avatar.text = Ui.initial(OngoingCall.label)
            avatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(OngoingCall.label))
            loadPhoto()

            val state = call.stateCompat()
            callState.text = stateName(state)

            // Mute / speaker can also be toggled from the notification, so
            // the buttons follow telecom's audio state rather than our own flags.
            CallService.instance?.callAudioState?.let { muted = it.isMuted }
            renderAudioToggles()
            updateProximity(state)

            // Which SIM the call is on — for incoming calls this is what tells
            // the user which of their numbers was rung.
            val sim = if (SimUtil.isDual(this)) SimUtil.byHandle(this, call.details.accountHandle) else null
            if (sim != null) {
                SimUtil.bind(simChipBadge, sim)
                simChipName.text = sim.name
                simChip.visibility = View.VISIBLE
            } else {
                simChip.visibility = View.GONE
            }

            val ringing = state == Call.STATE_RINGING
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            ringingButtons.visibility = if (ringing && !locked) View.VISIBLE else View.GONE
            ringingSlide.visibility = if (ringing && locked) View.VISIBLE else View.GONE
            activeBar.visibility = if (ringing) View.GONE else View.VISIBLE

            // Hold tile doubles as Resume while held.
            val holding = state == Call.STATE_HOLDING
            labelHold.text = if (holding) "Resume" else "Hold"
            btnHold.isEnabled = holding || (state == Call.STATE_ACTIVE && call.details.can(Call.Details.CAPABILITY_HOLD))
            btnHold.alpha = if (btnHold.isEnabled) 1f else 0.4f
            setToggle(btnHold, holding)

            // Unknown number: the tile and the chip under the name both save it.
            val number = call.details.handle?.schemeSpecificPart
            val known = number != null && OngoingCall.label != number &&
                ContactHelper.lookupContactUri(this, number) != null
            labelContact.text = if (known) "Contact" else "Add contact"
            btnContact.setImageResource(if (known) R.drawable.ic_person else R.drawable.ic_person_add)
            btnAddContact.visibility = if (!known && !number.isNullOrBlank() && !ringing) View.VISIBLE else View.GONE

            renderOtherCall(state)

            if (state == Call.STATE_ACTIVE && !timerRunning) {
                timerRunning = true
                val connected = call.details.connectTimeMillis
                callTimer.base = if (connected > 0)
                    SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connected)
                else SystemClock.elapsedRealtime()
                callTimer.visibility = View.VISIBLE
                callTimer.start()
            }
            if (state == Call.STATE_DISCONNECTED) {
                callTimer.stop()
                handler.postDelayed({ finish() }, 1200)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
