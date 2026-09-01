package dev.nihal.callassist

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.database.ContentObserver
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.widget.Chronometer
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** Sent by the gate notification: open straight to the Gate tab's log. */
        const val ACTION_SHOW_GATE = "dev.nihal.callassist.SHOW_GATE"

        /** Sent by the missed-call notification: open straight to Recents. */
        const val ACTION_SHOW_RECENTS = "dev.nihal.callassist.SHOW_RECENTS"
        private const val TAB_GATE = 3
    }

    private val bg = Executors.newSingleThreadExecutor()

    /**
     * Contacts and the call log used to be requeried on every resume and every
     * visit to Recents. The observers mark the data dirty instead, so a clean
     * resume or tab switch skips the load and the three list rebinds entirely.
     */
    private var dataDirty = true
    private var observingContacts = false
    private var observingCallLog = false
    private val dataObserver = object : ContentObserver(android.os.Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            dataDirty = true
            // Refresh live if the app is on screen (a call just ended, say);
            // debounced because deletes/syncs fire in bursts.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                navBar.removeCallbacks(observerReload)
                navBar.postDelayed(observerReload, 400)
            }
        }
    }
    private val observerReload = Runnable { reloadData() }

    // Keypad
    private lateinit var numberDisplay: EditText
    /** True while renderDialInput() is writing to numberDisplay itself. */
    private var renderingDial = false
    private lateinit var btnBackspace: ImageButton
    private lateinit var suggestionsList: RecyclerView
    private lateinit var suggestionsEmpty: TextView
    private lateinit var suggestionsAdapter: RowAdapter
    private var toneGen: ToneGenerator? = null

    // Dual-SIM selector beside the dial button
    private lateinit var simChip: View
    private lateinit var simChipBadge: TextView
    private lateinit var simChipIcon: ImageView
    private lateinit var simChipName: TextView

    // Recents / contacts
    private lateinit var recentsAdapter: RecentsAdapter
    private lateinit var recentsEmpty: TextView
    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var contactsEmpty: TextView
    private lateinit var contactSearch: EditText
    private var callLog: List<ContactsRepo.CallEntry> = emptyList()

    // Recents search / filters
    private lateinit var recentsSearch: EditText
    private lateinit var recentsSearchPanel: View
    private lateinit var recentsChips: View
    private lateinit var chipType: Chip
    private lateinit var btnRecentsFilter: ImageButton
    private var recentsSearchOpen = false

    // Recents selection mode (long-press)
    private lateinit var recentsTitle: TextView
    private lateinit var recentsSelectBar: View
    private lateinit var selectAllIcon: ImageView
    private var pendingDeleteIds: List<Long>? = null

    /** Call-type filter; index into [TYPE_FILTERS], 0 = all calls. */
    private var recentsTypeFilter = 0

    /** Call-time filter in days (1 = today), 0 = any time. */
    private var recentsDays = 0

    private data class TypeFilter(val label: String, val types: IntArray?)

    private val TYPE_FILTERS = listOf(
        TypeFilter("All calls", null),
        TypeFilter("Missed calls", intArrayOf(CallLog.Calls.MISSED_TYPE)),
        TypeFilter("Rejected calls", intArrayOf(CallLog.Calls.REJECTED_TYPE)),
        TypeFilter("Blocked calls", intArrayOf(CallLog.Calls.BLOCKED_TYPE)),
        TypeFilter("Outgoing calls", intArrayOf(CallLog.Calls.OUTGOING_TYPE)),
        TypeFilter(
            "Incoming calls",
            intArrayOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)
        )
    )

    // Gate
    private lateinit var logAdapter: GateLogAdapter
    private lateinit var logEmpty: TextView

    private lateinit var pages: Map<Int, View>
    private lateinit var navBar: GlassNavBar
    private var currentTab = 0
    private var keypadCollapsed = false

    /** Just past the nav bar's 180ms bubble animation. */
    private val TAB_SETTLE_MS = 200L
    private var pendingReload: Runnable? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                currentTab == 1 && recentsAdapter.selectionMode -> setRecentsSelection(false)
                currentTab == 1 && recentsSearchOpen -> setRecentsSearchOpen(false)
                keypadCollapsed -> setKeypadCollapsed(false)
                dialRaw().isNotEmpty() -> setDial("")
            }
        }
    }

    /** Fires on call set/clear, state and audio changes — keeps the return-to-call banner live. */
    private val callListener: () -> Unit = { runOnUiThread { renderCallBanner() } }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshGate() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshGate()
            reloadData(force = true)
            // READ_PHONE_STATE may have just arrived, which is what lists the SIMs.
            refreshSim()
            // A delete that was waiting on WRITE_CALL_LOG.
            pendingDeleteIds?.let { ids ->
                pendingDeleteIds = null
                if (has(Manifest.permission.WRITE_CALL_LOG)) deleteCallLogs(ids)
                else Toast.makeText(this, "Call log permission needed to delete", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifications.ensureChannels(this)

        navBar = findViewById(R.id.navBar)
        pages = mapOf(
            0 to findViewById(R.id.pageKeypad),
            1 to findViewById(R.id.pageRecents),
            2 to findViewById(R.id.pageContacts),
            3 to findViewById(R.id.pageGate)
        )
        navBar.setTabs(
            listOf(
                GlassNavBar.TabSpec(R.drawable.ic_dialpad, "Keypad", R.drawable.ic_dialpad_filled),
                GlassNavBar.TabSpec(R.drawable.ic_recents, "Recents", R.drawable.ic_recents_filled),
                GlassNavBar.TabSpec(R.drawable.ic_person_outline, "Contacts", R.drawable.ic_person),
                GlassNavBar.TabSpec(R.drawable.ic_shield_outline, "Gate", R.drawable.ic_shield)
            )
        )
        navBar.onTabSelected = { idx -> switchTab(idx) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setupKeypad()
        setupRecents()
        setupContacts()
        setupGate()

        for (id in intArrayOf(R.id.gearRecents, R.id.gearContacts, R.id.gearGate)) {
            findViewById<ImageButton>(id).setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        findViewById<View>(R.id.callBanner).setOnClickListener {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        OngoingCall.addListener(callListener)

        // First run: take the user to setup until the app is the default dialer.
        val rm = getSystemService(RoleManager::class.java)
        navBar.select(if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) 0 else TAB_GATE)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask: the launch intent sticks around for later getIntent() calls,
        // so replace it with the one that actually brought us to the front.
        intent?.let { setIntent(it) }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_GATE) {
            navBar.select(TAB_GATE)
            // The log itself is rebuilt in onResume's refreshGate().
            return
        }
        if (intent?.action == ACTION_SHOW_RECENTS) {
            navBar.select(1)
            return
        }
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        val raw = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return
        // Web tel: links often carry spaces/dashes ("tel:3125 0007")
        var number = PhoneNumberUtils.stripSeparators(raw)
        if (number.isEmpty()) return
        if (number.startsWith("+") && Prefs.iddEnabled(this)) {
            number = Prefs.iddCode(this) + number.drop(1)
        }
        navBar.select(0)
        setKeypadCollapsed(false)
        setDial(number)
    }

    override fun onResume() {
        super.onResume()
        refreshGate()
        refreshSim()
        reloadData()
        renderCallBanner()
    }

    /**
     * "Return to call" bar: shown whenever a call is live
     * and the user is looking at the dialer instead of the call screen.
     */
    private fun renderCallBanner() {
        val banner = findViewById<View>(R.id.callBanner)
        val call = OngoingCall.call
        val state = call?.stateCompat()
        if (call == null || state == Call.STATE_DISCONNECTED) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        findViewById<TextView>(R.id.callBannerName).text = OngoingCall.label
        val timer = findViewById<Chronometer>(R.id.callBannerTimer)
        val stateText = findViewById<TextView>(R.id.callBannerState)
        if (state == Call.STATE_ACTIVE) {
            val connected = call.details.connectTimeMillis
            timer.base = if (connected > 0)
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connected)
            else SystemClock.elapsedRealtime()
            timer.start()
            timer.visibility = View.VISIBLE
            stateText.text = "Ongoing call ·"
        } else {
            timer.stop()
            timer.visibility = View.GONE
            stateText.text = stateName(state!!)
        }
    }

    /** Re-reads the SIM list (it can change in Settings → SIM manager) and repaints the chip. */
    private fun refreshSim() {
        SimUtil.refresh(this)
        renderSimChip()
    }

    override fun onDestroy() {
        try {
            contentResolver.unregisterContentObserver(dataObserver)
        } catch (_: Exception) {
        }
        OngoingCall.removeListener(callListener)
        navBar.removeCallbacks(observerReload)
        pendingReload?.let { navBar.removeCallbacks(it) }
        toneGen?.release()
        bg.shutdown()
        super.onDestroy()
    }

    // ---------------- Keypad ----------------

    private data class Key(val digit: Char, val letters: String)

    private fun setupKeypad() {
        numberDisplay = findViewById(R.id.numberDisplay)
        btnBackspace = findViewById(R.id.btnBackspace)
        suggestionsList = findViewById(R.id.suggestionsList)
        suggestionsEmpty = findViewById(R.id.suggestionsEmpty)

        suggestionsAdapter = RowAdapter { row -> confirmCall(row.payload as String, row.title.toString()) }
        suggestionsList.layoutManager = LinearLayoutManager(this)
        suggestionsList.adapter = suggestionsAdapter
        suggestionsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (kotlin.math.abs(dy) > 6 && !keypadCollapsed && currentTab == 0) {
                    setKeypadCollapsed(true)
                }
            }
        })
        findViewById<View>(R.id.fabKeypad).setOnClickListener { setKeypadCollapsed(false) }

        val grid = findViewById<GridLayout>(R.id.keypadGrid)
        val keys = listOf(
            Key('1', ""), Key('2', "ABC"), Key('3', "DEF"),
            Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO"),
            Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ"),
            Key('*', ""), Key('0', "+"), Key('#', "")
        )
        val ripple = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        // Samsung phones ship a lighter system face under this family name; on other
        // devices Typeface.create silently falls back to the system default.
        val dialFont = Typeface.create("sec-roboto-light", Typeface.NORMAL)
        numberDisplay.typeface = dialFont
        numberDisplay.showSoftInputOnFocus = false
        // Paste / hardware keys bypass the keypad: re-normalise whatever landed.
        numberDisplay.doAfterTextChanged { s ->
            if (renderingDial) return@doAfterTextChanged
            val text = s?.toString().orEmpty()
            setDial(PhoneNumberUtils.stripSeparators(text), rawCursorAt(text, numberDisplay.selectionStart))
        }
        for (k in keys) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple.resourceId)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = dp(92) }
            }
            cell.addView(TextView(this).apply {
                text = k.digit.toString()
                textSize = 36f
                typeface = dialFont
                gravity = Gravity.CENTER
            })
            if (k.letters.isNotEmpty()) cell.addView(TextView(this).apply {
                text = k.letters
                textSize = 11f
                typeface = dialFont
                alpha = 0.6f
                gravity = Gravity.CENTER
            })
            if (k.digit == '1') cell.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_voicemail)
                imageTintList = ColorStateList.valueOf(getColor(R.color.textPrimary))
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(13))
            })
            cell.setOnClickListener {
                if (Prefs.keyHaptics(this)) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                playKeyTone(k.digit)
                insertDial(k.digit)
            }
            when (k.digit) {
                '0' -> cell.setOnLongClickListener {
                    insertDial('+'); true
                }
                '1' -> cell.setOnLongClickListener {
                    dialVoicemail()
                    true
                }
                else -> {}
            }
            grid.addView(cell)
        }

        btnBackspace.setOnClickListener { backspaceDial() }
        btnBackspace.setOnLongClickListener { setDial(""); true }

        findViewById<View>(R.id.btnDial).setOnClickListener {
            val n = dialRaw()
            if (n.isNotEmpty()) confirmCall(n)
        }

        simChip = findViewById(R.id.simChip)
        simChipBadge = findViewById(R.id.simChipBadge)
        simChipIcon = findViewById(R.id.simChipIcon)
        simChipName = findViewById(R.id.simChipName)
        simChip.setOnClickListener { SimUtil.showPicker(this) { renderSimChip() } }
        setDial("")
    }

    /**
     * Keypad SIM chip: the SIM the dial button will use, or a generic SIM
     * glyph + "Ask" when each call prompts. Hidden entirely on single-SIM phones.
     */
    private fun renderSimChip() {
        if (!SimUtil.isDual(this)) {
            simChip.visibility = View.GONE
            return
        }
        simChip.visibility = View.VISIBLE
        val sim = SimUtil.selected(this)
        if (sim != null) {
            SimUtil.bind(simChipBadge, sim)
            simChipBadge.visibility = View.VISIBLE
            simChipIcon.visibility = View.GONE
            simChipName.text = sim.name
        } else {
            simChipBadge.visibility = View.GONE
            simChipIcon.visibility = View.VISIBLE
            simChipName.text = "Ask"
        }
    }

    // The EditText is the source of truth for the dialled number; these helpers
    // edit it in "raw" (separator-free) space so the cursor survives reformatting.

    /** Dialable chars (digits, plus, star, hash) currently in the display, formatting stripped. */
    private fun dialRaw(): String = PhoneNumberUtils.stripSeparators(numberDisplay.text.toString())

    /** Number of dialable chars before [pos] in the formatted [text]. */
    private fun rawCursorAt(text: CharSequence, pos: Int): Int {
        var n = 0
        for (i in 0 until pos.coerceIn(0, text.length)) if (PhoneNumberUtils.isNonSeparator(text[i])) n++
        return n
    }

    private fun insertDial(c: Char) {
        val raw = dialRaw()
        val text = numberDisplay.text
        val start = rawCursorAt(text, numberDisplay.selectionStart.takeIf { it >= 0 } ?: text.length)
        val end = rawCursorAt(text, numberDisplay.selectionEnd.takeIf { it >= 0 } ?: text.length)
        setDial(raw.substring(0, start) + c + raw.substring(end), start + 1)
    }

    private fun backspaceDial() {
        val raw = dialRaw()
        if (raw.isEmpty()) return
        val text = numberDisplay.text
        var start = rawCursorAt(text, numberDisplay.selectionStart.takeIf { it >= 0 } ?: text.length)
        val end = rawCursorAt(text, numberDisplay.selectionEnd.takeIf { it >= 0 } ?: text.length)
        if (start == end) {
            if (start == 0) return
            start--
        }
        setDial(raw.substring(0, start) + raw.substring(end), start)
    }

    /** Replaces the number with [raw], placing the cursor after [rawCursor] dialable chars (default: end). */
    private fun setDial(raw: String, rawCursor: Int = raw.length) {
        val formatted =
            if (raw.all { it.isDigit() || it == '+' })
                PhoneNumberUtils.formatNumber(raw, Locale.getDefault().country) ?: raw
            else raw
        var pos = formatted.length
        var seen = 0
        for (i in formatted.indices) {
            if (seen == rawCursor) { pos = i; break }
            if (PhoneNumberUtils.isNonSeparator(formatted[i])) seen++
        }
        renderingDial = true
        numberDisplay.setText(formatted)
        numberDisplay.setSelection(pos.coerceIn(0, formatted.length))
        renderingDial = false
        renderDialInput()
    }

    /**
     * Dials voicemail as a plain tel: call. placeCall(voicemail:) is avoided on
     * purpose: on a SIM with no provisioned number (e.g. Singtel prepaid)
     * Samsung's telecom pops its own "Add voicemail number?" dialog and the
     * hand-off crashes us.
     */
    private fun dialVoicemail() {
        val saved = Prefs.voicemailNumber(this)
        if (saved.isNotEmpty()) {
            confirmCall(saved, "Voicemail")
            return
        }
        val simVm = try {
            getSystemService(TelephonyManager::class.java).voiceMailNumber
        } catch (_: Exception) {
            null
        }
        if (!simVm.isNullOrEmpty()) {
            confirmCall(simVm, "Voicemail")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "e.g. 1311"
        }
        Sheet(this)
            .title("Set voicemail number")
            .message("Your SIM doesn't report a voicemail number. Enter your carrier's access number (Singtel: 1311) and it'll be saved for next time.")
            .view(input)
            .negative()
            .positive("Save & call") {
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    Prefs.setVoicemailNumber(this, n)
                    confirmCall(n, "Voicemail")
                }
            }
            .show()
    }

    private fun renderDialInput() {
        val raw = dialRaw()
        btnBackspace.visibility = if (raw.isEmpty()) View.INVISIBLE else View.VISIBLE

        val accent = getColor(R.color.accent)
        val matches = ContactsRepo.search(raw)
        suggestionsAdapter.rows = matches.map { m ->
            val title: CharSequence = m.nameSpan?.let { span ->
                SpannableString(m.contact.name).apply {
                    val end = (span.last + 1).coerceAtMost(m.contact.name.length)
                    setSpan(ForegroundColorSpan(accent), span.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), span.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } ?: m.contact.name
            RowAdapter.Row(
                title = title,
                subtitle = "",
                meta = highlightNumber(fmt(m.number.number), m.digitSpan, accent),
                avatarSeed = m.contact.name,
                payload = m.number.number
            )
        }
        val showList = matches.isNotEmpty()
        suggestionsList.visibility = if (showList) View.VISIBLE else View.GONE
        suggestionsEmpty.visibility = if (showList) View.GONE else View.VISIBLE
        if (raw.isEmpty() && keypadCollapsed) setKeypadCollapsed(false)
        updateBackState()
    }

    /** Colors the digits of a formatted number whose digit-indices fall in span. */
    private fun highlightNumber(formatted: String, digitSpan: IntRange?, accent: Int): CharSequence {
        if (digitSpan == null) return formatted
        val sp = SpannableString(formatted)
        var d = 0
        for (i in formatted.indices) {
            if (formatted[i].isDigit()) {
                if (d in digitSpan) {
                    sp.setSpan(ForegroundColorSpan(accent), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(StyleSpan(Typeface.BOLD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                d++
            }
        }
        return sp
    }

    private fun setKeypadCollapsed(collapsed: Boolean) {
        if (keypadCollapsed == collapsed) return
        keypadCollapsed = collapsed
        val panel = findViewById<View>(R.id.keypadPanel)
        val fab = findViewById<View>(R.id.fabKeypad)
        if (collapsed) {
            panel.animate().translationY(panel.height.toFloat()).alpha(0f).setDuration(180)
                .withEndAction { panel.visibility = View.GONE }.start()
            fab.alpha = 0f
            fab.visibility = View.VISIBLE
            fab.animate().alpha(1f).setDuration(180).start()
        } else {
            panel.visibility = View.VISIBLE
            panel.animate().translationY(0f).alpha(1f).setDuration(180).start()
            fab.animate().alpha(0f).setDuration(150)
                .withEndAction { fab.visibility = View.GONE }.start()
        }
        updateBackState()
    }

    private fun updateBackState() {
        backCallback.isEnabled =
            (currentTab == 0 && (keypadCollapsed || dialRaw().isNotEmpty())) ||
                (currentTab == 1 && (recentsSearchOpen || recentsAdapter.selectionMode))
    }

    private fun playKeyTone(digit: Char) {
        if (!Prefs.keyTones(this)) return
        // Respect vibrate/silent mode — the DTMF stream would otherwise beep anyway.
        val am = getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_DTMF, 60)
            val tone = when (digit) {
                in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (digit - '0')
                '*' -> ToneGenerator.TONE_DTMF_S
                '#' -> ToneGenerator.TONE_DTMF_P
                else -> return
            }
            toneGen?.startTone(tone, 70)
        } catch (_: Exception) {
        }
    }

    // ---------------- Recents ----------------

    private fun setupRecents() {
        recentsEmpty = findViewById(R.id.recentsEmpty)
        recentsAdapter = RecentsAdapter(
            expandable = true,
            onCall = { confirmCall(it) },
            onMessage = { openSms(it) },
            onContact = { openOrAddContact(it) },
            onHistory = { showHistorySheet(it.number, it.title) }
        )
        findViewById<RecyclerView>(R.id.recentsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recentsAdapter
            // The list's bounds never depend on its contents, so skip the
            // parent re-measure on every data change.
            setHasFixedSize(true)
            // Rows are cheap but numerous; a deeper cache means switching back
            // to this tab rebinds instead of re-inflating.
            setItemViewCacheSize(12)
        }

        recentsSearch = findViewById(R.id.recentsSearch)
        recentsSearchPanel = findViewById(R.id.recentsSearchPanel)
        recentsChips = findViewById(R.id.recentsChipsScroll)
        chipType = findViewById(R.id.chipType)
        btnRecentsFilter = findViewById(R.id.btnRecentsFilter)

        findViewById<View>(R.id.btnRecentsSearch).setOnClickListener {
            setRecentsSearchOpen(!recentsSearchOpen)
        }
        findViewById<View>(R.id.btnRecentsSearchClose).setOnClickListener {
            setRecentsSearchOpen(false)
        }
        recentsSearch.doAfterTextChanged { renderRecents() }
        recentsSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else false
        }

        btnRecentsFilter.setOnClickListener { showRecentsFilterDialog() }

        recentsTitle = findViewById(R.id.recentsTitle)
        recentsSelectBar = findViewById(R.id.recentsSelectBar)
        selectAllIcon = findViewById(R.id.selectAllIcon)
        recentsAdapter.onLongPress = { e ->
            setRecentsSelection(true)
            recentsAdapter.toggle(e)
        }
        recentsAdapter.onSelectionChanged = { renderSelectionState() }
        findViewById<View>(R.id.btnSelectAll).setOnClickListener {
            recentsAdapter.selectAll(!recentsAdapter.allSelected())
        }
        findViewById<View>(R.id.btnSelectCancel).setOnClickListener { setRecentsSelection(false) }
        findViewById<View>(R.id.btnSelCopy).setOnClickListener { copySelectedNumbers() }
        findViewById<View>(R.id.btnSelDelete).setOnClickListener { confirmDeleteSelected() }
        chipType.setOnCloseIconClickListener { setRecentsTypeFilter(0) }

        val chipDays = mapOf(R.id.chipToday to 1, R.id.chip7 to 7, R.id.chip30 to 30)
        findViewById<ChipGroup>(R.id.chipTimeGroup).setOnCheckedStateChangeListener { _, checked ->
            recentsDays = checked.firstOrNull()?.let { chipDays[it] } ?: 0
            renderRecents()
        }
    }

    // ---- Selection mode ----

    private fun setRecentsSelection(on: Boolean) {
        if (recentsAdapter.selectionMode == on) return
        recentsAdapter.setSelectionMode(on)
        val vis = if (on) View.VISIBLE else View.GONE
        val hidden = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnSelectAll).visibility = vis
        findViewById<View>(R.id.btnSelectCancel).visibility = vis
        btnRecentsFilter.visibility = hidden
        findViewById<View>(R.id.gearRecents).visibility = hidden
        recentsSelectBar.visibility = vis
        navBar.visibility = hidden
        renderSelectionState()
        updateBackState()
    }

    private fun renderSelectionState() {
        if (!recentsAdapter.selectionMode) {
            recentsTitle.text = "Phone"
            return
        }
        val n = recentsAdapter.selectedCount()
        recentsTitle.text = if (n == 0) "Select calls" else "$n selected"
        val all = recentsAdapter.allSelected()
        selectAllIcon.setImageResource(if (all) R.drawable.ic_check_circle else R.drawable.ic_circle)
        selectAllIcon.imageTintList = ColorStateList.valueOf(
            getColor(if (all) R.color.accent else R.color.textSecondary)
        )
    }

    private fun copySelectedNumbers() {
        val numbers = recentsAdapter.selectedEntries().map { fmt(it.number) }.distinct()
        if (numbers.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Phone number", numbers.joinToString("\n")))
        Toast.makeText(
            this,
            if (numbers.size == 1) "Copied ${numbers[0]}" else "Copied ${numbers.size} numbers",
            Toast.LENGTH_SHORT
        ).show()
        setRecentsSelection(false)
    }

    private fun confirmDeleteSelected() {
        val entries = recentsAdapter.selectedEntries()
        val ids = entries.flatMap { it.ids }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        val n = ids.size
        Sheet(this)
            .title(if (n == 1) "Delete call log?" else "Delete $n call logs?")
            .message("This removes ${if (n == 1) "it" else "them"} from the phone's call history.")
            .negative()
            .positive("Delete", destructive = true) {
                if (has(Manifest.permission.WRITE_CALL_LOG)) {
                    deleteCallLogs(ids)
                } else {
                    pendingDeleteIds = ids
                    permLauncher.launch(arrayOf(Manifest.permission.WRITE_CALL_LOG))
                }
            }
            .show()
    }

    private fun deleteCallLogs(ids: List<Long>) {
        if (bg.isShutdown) return
        bg.execute {
            val deleted = try {
                contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN (${ids.joinToString(",")})",
                    null
                )
            } catch (e: Exception) {
                -1
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (deleted < 0) "Could not delete call logs"
                    else if (deleted == 1) "Call log deleted" else "$deleted call logs deleted",
                    Toast.LENGTH_SHORT
                ).show()
                setRecentsSelection(false)
                reloadData(force = true)
            }
        }
    }

    private fun setRecentsSearchOpen(open: Boolean) {
        if (recentsSearchOpen == open) return
        recentsSearchOpen = open
        recentsSearchPanel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            recentsSearch.requestFocus()
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(recentsSearch, InputMethodManager.SHOW_IMPLICIT)
        } else {
            hideKeyboard(recentsSearch)
            recentsSearch.setText("")
        }
        updateRecentsChips()
        renderRecents()
        updateBackState()
    }

    private fun setRecentsTypeFilter(idx: Int) {
        recentsTypeFilter = idx
        val active = idx != 0
        btnRecentsFilter.imageTintList = ColorStateList.valueOf(
            getColor(if (active) R.color.accent else R.color.textSecondary)
        )
        chipType.text = TYPE_FILTERS[idx].label
        chipType.visibility = if (active) View.VISIBLE else View.GONE
        updateRecentsChips()
        renderRecents()
    }

    /**
     * The chip strip shows while searching or while a type filter is on. When
     * it hides, any time chip goes with it — a filter the user can't see
     * shouldn't keep narrowing the list.
     */
    private fun updateRecentsChips() {
        val show = recentsSearchOpen || recentsTypeFilter != 0
        recentsChips.visibility = if (show) View.VISIBLE else View.GONE
        // clearCheck() fires the group listener, which resets recentsDays.
        if (!show && recentsDays != 0) findViewById<ChipGroup>(R.id.chipTimeGroup).clearCheck()
    }

    /** "Filter calls" sheet: radio rows, Cancel | OK. */
    private fun showRecentsFilterDialog() {
        Sheet(this)
            .title("Filter calls")
            .singleChoice(TYPE_FILTERS.map { it.label }, recentsTypeFilter) { setRecentsTypeFilter(it) }
            .negative()
            .positive("OK")
            .show()
    }

    private fun hideKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Display name for a log entry, the same way the list resolves it. */
    private fun entryName(e: ContactsRepo.CallEntry): String? =
        e.name?.takeIf { it.isNotBlank() } ?: ContactsRepo.lookupNameCached(e.number)

    private fun filteredCallLog(): List<ContactsRepo.CallEntry> {
        val types = TYPE_FILTERS[recentsTypeFilter].types
        val cutoff = if (recentsDays > 0) {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(recentsDays - 1))
            }.timeInMillis
        } else 0L
        val q = if (recentsSearchOpen) recentsSearch.text.toString().trim().lowercase() else ""
        val qDigits = q.filter { it.isDigit() }
        if (types == null && cutoff == 0L && q.isEmpty()) return callLog
        return callLog.filter { e ->
            (types == null || e.type in types) &&
                e.date >= cutoff &&
                (q.isEmpty() ||
                    entryName(e)?.lowercase()?.contains(q) == true ||
                    (qDigits.isNotEmpty() && e.number.filter { it.isDigit() }.contains(qDigits)))
        }
    }

    private fun openSms(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")))
        } catch (_: Exception) {
            Toast.makeText(this, "No messaging app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOrAddContact(number: String) {
        try {
            val uri = ContactHelper.lookupContactUri(this, number)
            if (uri != null) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } else {
                startActivity(
                    Intent(Intent.ACTION_INSERT_OR_EDIT)
                        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
                )
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open contacts app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHistorySheet(number: String, title: String) {
        val digits = number.filter { it.isDigit() }
        val history = callLog.filter { it.number.filter { d -> d.isDigit() } == digits }
        var dialog: BottomSheetDialog? = null
        val sheetAdapter = RecentsAdapter(expandable = false, onCall = {
            dialog?.dismiss()
            confirmCall(it, title)
        })
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sheetAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(440))
        }
        sheetAdapter.items = buildRecentsItems(history)
        dialog = Sheet(this).title(title).view(list).show()
    }

    private fun dayLabel(ts: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today"
            sameYear && dayDiff == 1 -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ts))
        }
    }

    private fun buildRecentsItems(log: List<ContactsRepo.CallEntry>): List<RecentsAdapter.Item> {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        // Merge consecutive entries with the same number, type, and day.
        class Group(val e: ContactsRepo.CallEntry, var count: Int, val ids: MutableList<Long>)

        val merged = ArrayList<Group>()
        val dual = SimUtil.isDual(this)
        for (e in log) {
            val last = merged.lastOrNull()
            if (last != null && last.e.type == e.type &&
                last.e.number.filter { it.isDigit() } == e.number.filter { it.isDigit() } &&
                last.e.accountId == e.accountId &&
                dayLabel(last.e.date) == dayLabel(e.date)
            ) {
                last.count++
                last.ids.add(e.id)
            } else {
                merged.add(Group(e, 1, mutableListOf(e.id)))
            }
        }

        val items = ArrayList<RecentsAdapter.Item>()
        var i = 0
        while (i < merged.size) {
            val label = dayLabel(merged[i].e.date)
            var j = i
            while (j < merged.size && dayLabel(merged[j].e.date) == label) j++
            items.add(RecentsAdapter.Item.Header(label))
            for (k in i until j) {
                val g = merged[k]
                val name = g.e.name?.takeIf { it.isNotBlank() }
                    ?: ContactsRepo.lookupNameCached(g.e.number)
                    ?: fmt(g.e.number)
                val bg = groupBg(k - i, j - i)
                items.add(
                    RecentsAdapter.Item.Entry(
                        title = name,
                        count = g.count,
                        time = timeFmt.format(Date(g.e.date)),
                        type = g.e.type,
                        number = g.e.number,
                        duration = g.e.duration,
                        bg = bg,
                        divider = k < j - 1,
                        ids = g.ids,
                        sim = if (dual) SimUtil.byLogColumns(this, g.e.accountComponent, g.e.accountId) else null
                    )
                )
            }
            i = j
        }
        return items
    }

    /** Rounded-corner background for row [index] of a card holding [size] rows. */
    private fun groupBg(index: Int, size: Int): Int = when {
        size == 1 -> R.drawable.bg_group_single
        index == 0 -> R.drawable.bg_group_top
        index == size - 1 -> R.drawable.bg_group_bottom
        else -> R.drawable.bg_group_mid
    }

    private fun renderRecents() {
        val shown = filteredCallLog()
        recentsAdapter.items = buildRecentsItems(shown)
        val hasPerm = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        recentsEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        recentsEmpty.text = when {
            !hasPerm -> "Call log permission needed — grant it in the Gate tab"
            callLog.isEmpty() -> "No calls yet"
            else -> "No matching calls"
        }
    }

    // ---------------- Contacts ----------------

    private fun setupContacts() {
        contactsEmpty = findViewById(R.id.contactsEmpty)
        contactSearch = findViewById(R.id.contactSearch)
        contactsAdapter = ContactsAdapter(
            onCall = { c, n ->
                if (n != null) confirmCall(n.number, c.name)
                else pickNumber(c, "Call which number?") { confirmCall(it.number, c.name) }
            },
            onMessage = { c -> pickNumber(c, "Message which number?") { openSms(it.number) } },
            onEdit = { editContact(it) },
            onBlock = { c -> pickNumber(c, "Block which number?") { confirmBlock(it.number) } }
        )
        findViewById<RecyclerView>(R.id.contactsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(12)
        }
        val clear = findViewById<View>(R.id.contactSearchClear)
        clear.setOnClickListener { contactSearch.setText("") }
        contactSearch.doAfterTextChanged {
            clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            renderContacts()
        }
        contactSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(v)
                true
            } else false
        }
    }

    /** Runs [then] with the contact's only number, or asks which one first. */
    private fun pickNumber(
        c: ContactsRepo.Contact,
        title: String,
        then: (ContactsRepo.PhoneEntry) -> Unit
    ) {
        if (c.numbers.size == 1) {
            then(c.numbers[0])
            return
        }
        val items = c.numbers.map { "${it.label.ifBlank { "Phone" }}  ${fmt(it.number)}" }
        Sheet(this).title(title).items(items) { then(c.numbers[it]) }.negative().show()
    }

    private fun editContact(contact: ContactsRepo.Contact) {
        try {
            startActivity(
                Intent(Intent.ACTION_EDIT).setData(
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id)
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open contact editor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderContacts() {
        val q = contactSearch.text.toString().trim().lowercase()
        val qDigits = q.filter { it.isDigit() }
        val numberSearch = qDigits.length >= 3
        val accent = getColor(R.color.accent)
        val list = ContactsRepo.contacts.filter { c ->
            q.isEmpty() ||
                c.name.lowercase().contains(q) ||
                (numberSearch && c.numbers.any { it.digits.contains(qDigits) })
        }

        fun entry(c: ContactsRepo.Contact, index: Int, size: Int): ContactsAdapter.Item.Entry {
            // Highlight what matched: the name run, or else the
            // digits inside the matching number.
            val nameHit = if (q.isEmpty()) -1 else c.name.lowercase().indexOf(q)
            val title: CharSequence = if (nameHit >= 0) {
                SpannableString(c.name).apply {
                    setSpan(ForegroundColorSpan(accent), nameHit, nameHit + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), nameHit, nameHit + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else c.name
            val subtitle: CharSequence = if (nameHit < 0 && numberSearch) {
                val parts = c.numbers.map { n ->
                    val at = n.digits.indexOf(qDigits)
                    if (at >= 0) highlightNumber(fmt(n.number), at until at + qDigits.length, accent)
                    else fmt(n.number)
                }
                android.text.TextUtils.concat(*parts.flatMapIndexed { i, p ->
                    if (i == 0) listOf(p) else listOf("  ·  ", p)
                }.toTypedArray())
            } else c.numbers.joinToString("  ·  ") { fmt(it.number) }
            return ContactsAdapter.Item.Entry(
                contact = c,
                title = title,
                subtitle = subtitle,
                bg = groupBg(index, size),
                divider = index < size - 1
            )
        }

        val items = ArrayList<ContactsAdapter.Item>()
        fun addGroup(header: String, group: List<ContactsRepo.Contact>, count: String = "") {
            if (group.isEmpty()) return
            items.add(ContactsAdapter.Item.Header(header, count))
            group.forEachIndexed { i, c -> items.add(entry(c, i, group.size)) }
        }
        if (q.isEmpty()) {
            addGroup("Favourites", list.filter { it.starred })
            // Contacts arrive starred-first from the provider; regroup by
            // initial so every letter gets its own card, '#' last.
            val byLetter = list.sortedBy { it.name.lowercase() }
                .groupBy { Ui.initial(it.name) }
            for ((letter, group) in byLetter.entries.sortedWith(
                compareBy({ it.key == "#" }, { it.key })
            )) addGroup(letter, group)
        } else {
            addGroup("Contacts", list.sortedBy { it.name.lowercase() }, "${list.size} found")
        }
        contactsAdapter.items = items

        val hasPerm = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        contactsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        contactsEmpty.text = when {
            !hasPerm -> "Contacts permission needed — grant it in the Gate tab"
            q.isEmpty() -> "No contacts"
            else -> "No matching contacts"
        }
    }

    // ---------------- Gate / settings ----------------

    private fun setupGate() {
        logEmpty = findViewById(R.id.logEmpty)
        logAdapter = GateLogAdapter()
        findViewById<RecyclerView>(R.id.logList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
        }
        val switchEnabled = findViewById<Switch>(R.id.switchEnabled)
        val editContact = findViewById<EditText>(R.id.editContact)
        val editCode = findViewById<EditText>(R.id.editCode)

        switchEnabled.isChecked = Prefs.enabled(this)
        editContact.setText(Prefs.contactName(this))
        editCode.setText(Prefs.gateCode(this))

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            refreshGate()
        }
        val switchNotify = findViewById<Switch>(R.id.switchNotify)
        switchNotify.isChecked = Prefs.notifyGate(this)
        switchNotify.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotifyGate(this, checked)
        }
        findViewById<TextView>(R.id.versionText).text = try {
            "Call Assist v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "Call Assist"
        }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            Prefs.setContactName(this, editContact.text.toString().trim())
            val code = editCode.text.toString().trim().filter { it.isDigit() || it == '#' || it == '*' }
            Prefs.setGateCode(this, code.ifEmpty { Prefs.DEFAULT_CODE })
            editCode.setText(Prefs.gateCode(this))
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refreshGate()
        }
        findViewById<View>(R.id.btnRefreshLog).setOnClickListener { refreshGate() }
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLog(this)
            refreshGate()
        }
    }

    /**
     * One thing that has to be true for the gate to open. [blocking] separates
     * "automation cannot run" from "it runs, but something is degraded" — the
     * headline reports the worst of the two, and only the failures get a row.
     */
    private data class Check(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val blocking: Boolean,
        val fix: (() -> Unit)?
    )

    private fun gateChecks(): List<Check> {
        val rm = getSystemService(RoleManager::class.java)
        val fsi = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val missingPerms = mutableListOf<String>().apply {
            if (!has(Manifest.permission.READ_CONTACTS)) add("Contacts")
            if (!has(Manifest.permission.READ_CALL_LOG)) add("Call log")
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) add("Notifications")
        }
        return listOf(
            Check(
                "Set as default phone app",
                "Required — the gate can't be answered without it",
                rm.isRoleHeld(RoleManager.ROLE_DIALER),
                blocking = true,
                fix = {
                    if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    }
                }
            ),
            Check(
                "Grant permissions",
                if (missingPerms.isEmpty()) "" else "${missingPerms.joinToString(", ")} — tap to allow",
                missingPerms.isEmpty(),
                blocking = true,
                fix = { requestGatePerms() }
            ),
            Check(
                "Turn on automation",
                "Auto-answer is switched off below",
                Prefs.enabled(this),
                blocking = true,
                fix = null
            ),
            Check(
                "Allow full-screen call alerts",
                "Calls show as a banner only",
                fsi,
                blocking = false,
                fix = { openFsiSettings() }
            ),
            Check(
                "Exempt from battery optimization",
                "Android may delay or kill the app",
                getSystemService(android.os.PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(packageName),
                blocking = false,
                fix = { openBatterySettings() }
            )
        )
    }

    private fun requestGatePerms() {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    /** Android 14+ full-screen-intent permission page. */
    private fun openFsiSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Open Settings → Apps → Call Assist → Notifications", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        // The direct request dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        // which Play flags; the settings list gets there without it.
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Open Settings → Apps → Call Assist → Battery", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshGate() {
        val checks = gateChecks()
        val issues = checks.filter { !it.ok }
        val blockers = issues.count { it.blocking }

        val badge = findViewById<ImageView>(R.id.statusBadge)
        val headline = findViewById<TextView>(R.id.statusHeadline)
        val sub = findViewById<TextView>(R.id.statusSub)
        val tint = when {
            blockers > 0 -> getColor(R.color.red)
            issues.isNotEmpty() -> getColor(R.color.amber)
            else -> getColor(R.color.green)
        }
        // mutate() so tinting this pill doesn't recolour every other view
        // sharing bg_status_pill from the resource cache — the issue dots below
        // use the same drawable.
        badge.background.mutate().setTint(tint)
        badge.setImageResource(if (issues.isEmpty()) R.drawable.ic_check_small else R.drawable.ic_gate_warn)
        // Both glyphs sit on the coloured pill, so force white over whatever
        // tint they carry (ic_gate_warn defaults to a grey control colour).
        badge.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        headline.text = when {
            blockers > 0 -> "Automation is off"
            issues.isNotEmpty() -> "Ready, with warnings"
            else -> "Ready"
        }
        headline.setTextColor(if (blockers > 0) tint else getColor(R.color.textPrimary))
        sub.text = when {
            blockers > 0 -> issues.first { it.blocking }.detail
            else -> "${Prefs.contactName(this)}  →  ${Prefs.gateCode(this)}"
        }

        // Only the unmet checks get a row, so a healthy setup is a single line
        // instead of seven ticks and two buttons that do nothing.
        val box = findViewById<LinearLayout>(R.id.statusIssues)
        box.removeAllViews()
        for (c in issues) {
            val row = layoutInflater.inflate(R.layout.item_status_issue, box, false)
            row.findViewById<TextView>(R.id.issueTitle).text = c.title
            row.findViewById<View>(R.id.issueDot).background.mutate().setTint(
                if (c.blocking) getColor(R.color.red) else getColor(R.color.amber)
            )
            val detail = row.findViewById<TextView>(R.id.issueDetail)
            detail.text = c.detail
            detail.visibility = if (c.detail.isEmpty()) View.GONE else View.VISIBLE
            val chevron = row.findViewById<View>(R.id.issueChevron)
            if (c.fix != null) {
                row.setOnClickListener { c.fix.invoke() }
            } else {
                // Nothing to launch (the automation switch is right below).
                row.isClickable = false
                chevron.visibility = View.INVISIBLE
            }
            if (box.childCount > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin =
                    resources.getDimensionPixelSize(R.dimen.status_row_gap)
            }
            box.addView(row)
        }
        box.visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE

        val sessions = GateLog.sessions(Prefs.readLogEntries(this))
        logAdapter.items = GateLog.buildItems(sessions)
        logEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.logList).visibility =
            if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------- Shared ----------------

    /**
     * The page swap itself is cheap, but re-querying contacts/call log and
     * re-binding three lists is not — and running it on the frames the nav bar
     * bubble is animating shows up as a visible stutter. So the visibility
     * change happens now (the user sees the new page immediately) and the
     * expensive refresh is posted until after the ~180ms bubble animation.
     */
    private fun switchTab(idx: Int) {
        currentTab = idx
        // INVISIBLE rather than GONE for the pages we're leaving: a GONE page
        // is dropped from layout, so coming back to it costs a full measure +
        // layout of the page on the very frame the bubble is sliding. INVISIBLE
        // keeps it measured, which makes the swap a draw-only change.
        // Only touch visibility for pages whose state actually changes —
        // re-setting VISIBLE on the current page would re-trigger layout.
        pages.forEach { (i, v) ->
            val want = if (i == idx) View.VISIBLE else View.INVISIBLE
            if (v.visibility != want) v.visibility = want
        }
        if (idx == 0) setKeypadCollapsed(false)
        if (idx != 1 && recentsAdapter.selectionMode) setRecentsSelection(false)
        updateBackState()

        pendingReload?.let { navBar.removeCallbacks(it) }
        if (idx == 1) {
            Notifications.clearMissed(this)
            val r = Runnable { pendingReload = null; reloadData() }
            pendingReload = r
            navBar.postDelayed(r, TAB_SETTLE_MS)
        }
    }

    private fun ensureObservers() {
        try {
            if (!observingContacts && has(Manifest.permission.READ_CONTACTS)) {
                contentResolver.registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, dataObserver
                )
                observingContacts = true
            }
            if (!observingCallLog && has(Manifest.permission.READ_CALL_LOG)) {
                contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, dataObserver)
                observingCallLog = true
            }
        } catch (_: Exception) {
        }
    }

    private fun reloadData(force: Boolean = false) {
        if (bg.isShutdown) return
        ensureObservers()
        // Until both observers are in place a clean flag can't be trusted.
        if (!force && !dataDirty && observingContacts && observingCallLog) return
        // Cleared before the load, so a change arriving mid-load re-dirties.
        dataDirty = false
        bg.execute {
            ContactsRepo.load(this)
            val log = ContactsRepo.loadCallLog(this)
            runOnUiThread {
                callLog = log
                renderRecents()
                renderContacts()
                renderDialInput()
            }
        }
    }

    private fun confirmCall(number: String, name: String? = null) {
        if (!Prefs.confirmCall(this)) {
            placeCall(number)
            return
        }
        val contact = ContactsRepo.lookupCached(number)
        val known = name?.takeIf { it.isNotBlank() } ?: contact?.name
        val display = known ?: fmt(number)

        val view = layoutInflater.inflate(R.layout.dialog_confirm_call, null)
        val dialog = AlertDialog.Builder(this, R.style.GlassDialog)
            .setView(view)
            .create()

        view.findViewById<TextView>(R.id.dlgAvatar).apply {
            text = Ui.initial(display)
            backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(display))
        }
        Ui.loadPhoto(this, view.findViewById(R.id.dlgPhoto), contact?.photoUri)
        view.findViewById<TextView>(R.id.dlgName).text = display
        // For an unknown number the name line already shows it — don't repeat it.
        view.findViewById<TextView>(R.id.dlgNumber).apply {
            if (known != null) {
                text = fmt(number)
            } else {
                visibility = View.GONE
            }
        }
        // Dual SIM: segmented switcher, preselected to the app-wide choice. The
        // pick is for this call only; the keypad chip / Settings change the default.
        val callBtn = view.findViewById<View>(R.id.dlgCall)
        val sims = SimUtil.sims(this)
        var chosenSim: PhoneAccountHandle? = null
        if (sims.size > 1) {
            val simRow = view.findViewById<LinearLayout>(R.id.dlgSimRow)
            simRow.visibility = View.VISIBLE
            chosenSim = SimUtil.selected(this)?.handle
            val segments = ArrayList<View>()
            fun paint() {
                segments.forEachIndexed { i, v -> v.isSelected = sims[i].handle == chosenSim }
                // "Ask every time" starts with nothing picked; Call waits for a pick.
                val ready = chosenSim != null
                callBtn.isEnabled = ready
                callBtn.alpha = if (ready) 1f else 0.45f
            }
            for ((i, s) in sims.withIndex()) {
                val seg = simSegment(s)
                seg.setOnClickListener { chosenSim = s.handle; paint() }
                simRow.addView(
                    seg,
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(8) }
                )
                segments.add(seg)
            }
            paint()
        }
        view.findViewById<TextView>(R.id.dlgCancel).setOnClickListener { dialog.dismiss() }
        callBtn.setOnClickListener {
            dialog.dismiss()
            placeCall(number, chosenSim)
        }

        // Real GPU blur of the content behind, when the system allows it.
        Glass.applyDialogBlur(dialog, view)

        dialog.show()
        // Floating dialogs default to a narrow platform width; widen to match
        // the app's card gutters.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** One pill of the confirmation dialog's SIM switcher: badge + name. */
    private fun simSegment(sim: SimUtil.Sim): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_sim_toggle)
            setPadding(dp(12), 0, dp(14), 0)
            addView(SimUtil.badge(this@MainActivity, sim))
            addView(TextView(this@MainActivity).apply {
                text = sim.name
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.textPrimary))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7) })
        }

    /** "Ask every time" prompt, used when the confirmation dialog is off. */
    private fun askSim(number: String) {
        val sims = SimUtil.sims(this)
        Sheet(this)
            .title("Call with")
            .items(sims.map { SimUtil.twoLine(this, it) }, sims.map { SimUtil.badge(this, it, 22) }) {
                placeCall(number, sims[it].handle)
            }
            .negative()
            .show()
    }

    private fun placeCall(number: String, account: PhoneAccountHandle? = null) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        val tm = getSystemService(TelecomManager::class.java)
        val chosen = account ?: when (Prefs.simMode(this)) {
            Prefs.SIM_FIXED -> SimUtil.byId(this, Prefs.simId(this))?.handle
            Prefs.SIM_ASK -> {
                if (SimUtil.isDual(this)) {
                    askSim(number)
                    return
                }
                null
            }
            else -> null
        }
        val extras = Bundle()
        chosen?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        try {
            tm.placeCall(Uri.fromParts("tel", number, null), extras)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not place call: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmBlock(number: String) {
        Sheet(this)
            .title("Block ${fmt(number)}?")
            .message("Calls from this number will be rejected system-wide. You can unblock it in Settings → Blocked numbers.")
            .negative()
            .positive("Block", destructive = true) { BlockedNumbersActivity.block(this, number) }
            .show()
    }

    private fun fmt(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
