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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.util.TypedValue
import android.widget.Button
import android.widget.Chronometer
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InCallActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var boundCall: Call? = null
    private var muted = false
    private var speaker = false
    private var timerRunning = false
    private var photoLoaded = false

    private lateinit var bgPhoto: ImageView
    private lateinit var scrim: View
    private lateinit var avatar: TextView
    private lateinit var avatarPhoto: ImageView
    private lateinit var callerName: TextView
    private lateinit var callState: TextView
    private lateinit var callTimer: Chronometer
    private lateinit var ringingButtons: View
    private lateinit var ringingSlide: View
    private lateinit var activeBar: View
    private lateinit var keypad: GridLayout
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var slideAnswer: SeekBar

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = render()
    }

    private val ongoingListener: () -> Unit = { runOnUiThread { bindCall() } }

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

        bgPhoto = findViewById(R.id.bgPhoto)
        scrim = findViewById(R.id.scrim)
        avatar = findViewById(R.id.avatar)
        avatarPhoto = findViewById(R.id.avatarPhoto)
        callerName = findViewById(R.id.callerName)
        callState = findViewById(R.id.callState)
        callTimer = findViewById(R.id.callTimer)
        ringingButtons = findViewById(R.id.ringingButtons)
        ringingSlide = findViewById(R.id.ringingSlide)
        activeBar = findViewById(R.id.activeBar)
        keypad = findViewById(R.id.keypad)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        slideAnswer = findViewById(R.id.slideAnswer)

        avatarPhoto.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatarPhoto.clipToOutline = true

        findViewById<Button>(R.id.btnAnswer).setOnClickListener { answer() }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { boundCall?.reject(false, null) }
        findViewById<Button>(R.id.btnMessageLocked).setOnClickListener { showQuickReplies() }
        findViewById<Button>(R.id.btnMessageSlide).setOnClickListener { showQuickReplies() }
        findViewById<ImageButton>(R.id.btnHangup).setOnClickListener { boundCall?.disconnect() }
        findViewById<Button>(R.id.btnResume).setOnClickListener { boundCall?.unhold() }

        btnMute.setOnClickListener {
            muted = !muted
            CallService.instance?.setMuted(muted)
            setToggle(btnMute, muted)
        }
        btnSpeaker.setOnClickListener {
            speaker = !speaker
            CallService.instance?.setAudioRoute(
                if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            )
            setToggle(btnSpeaker, speaker)
        }
        findViewById<ImageButton>(R.id.btnKeypad).setOnClickListener {
            keypad.visibility = if (keypad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        slideAnswer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress >= 85) {
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

    private fun answer() {
        boundCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
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

    private fun buildKeypad() {
        val ripple = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        val keys = "123456789*0#"
        for (k in keys) {
            val b = Button(this)
            b.text = k.toString()
            b.textSize = 24f
            b.setBackgroundResource(ripple.resourceId)
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = (52 * resources.displayMetrics.density).toInt()
            b.layoutParams = lp
            b.setOnClickListener {
                val c = boundCall ?: return@setOnClickListener
                c.playDtmfTone(k)
                handler.postDelayed({ c.stopDtmfTone() }, 250)
            }
            keypad.addView(b)
        }
    }

    private fun bindCall() {
        boundCall?.unregisterCallback(callback)
        boundCall = OngoingCall.call
        boundCall?.registerCallback(callback)
        photoLoaded = false
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
            }
        } catch (_: Exception) {
        }
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

            val ringing = state == Call.STATE_RINGING
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            ringingButtons.visibility = if (ringing && !locked) View.VISIBLE else View.GONE
            ringingSlide.visibility = if (ringing && locked) View.VISIBLE else View.GONE
            activeBar.visibility = if (ringing) View.GONE else View.VISIBLE
            findViewById<Button>(R.id.btnResume).visibility =
                if (state == Call.STATE_HOLDING) View.VISIBLE else View.GONE

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
}
