package dev.nihal.callassist

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
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
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val bg = Executors.newSingleThreadExecutor()

    // Keypad
    private val dialInput = StringBuilder()
    private lateinit var numberDisplay: TextView
    private lateinit var btnBackspace: ImageButton
    private lateinit var suggestionsList: RecyclerView
    private lateinit var suggestionsEmpty: TextView
    private lateinit var suggestionsAdapter: RowAdapter
    private var toneGen: ToneGenerator? = null

    // Recents / contacts
    private lateinit var recentsAdapter: RecentsAdapter
    private lateinit var recentsEmpty: TextView
    private lateinit var contactsAdapter: RowAdapter
    private lateinit var contactsEmpty: TextView
    private lateinit var contactSearch: EditText
    private var callLog: List<ContactsRepo.CallEntry> = emptyList()

    // Gate
    private lateinit var statusText: TextView
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
                keypadCollapsed -> setKeypadCollapsed(false)
                dialInput.isNotEmpty() -> {
                    dialInput.clear()
                    renderDialInput()
                }
            }
        }
    }

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshGate() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshGate()
            reloadData()
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
                GlassNavBar.TabSpec(R.drawable.ic_history, "Recents", R.drawable.ic_history_filled),
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

        // First run: take the user to setup until the app is the default dialer.
        val rm = getSystemService(RoleManager::class.java)
        navBar.select(if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) 0 else 3)
        handleDialIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        val number = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return
        dialInput.clear()
        dialInput.append(number)
        navBar.select(0)
        setKeypadCollapsed(false)
        renderDialInput()
    }

    override fun onResume() {
        super.onResume()
        refreshGate()
        reloadData()
    }

    override fun onDestroy() {
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
        for (k in keys) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(ripple.resourceId)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = dp(84) }
            }
            cell.addView(TextView(this).apply {
                text = k.digit.toString()
                textSize = 34f
                gravity = Gravity.CENTER
            })
            if (k.letters.isNotEmpty()) cell.addView(TextView(this).apply {
                text = k.letters
                textSize = 11f
                alpha = 0.6f
                gravity = Gravity.CENTER
            })
            cell.setOnClickListener {
                if (Prefs.keyHaptics(this)) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                playKeyTone(k.digit)
                dialInput.append(k.digit)
                renderDialInput()
            }
            when (k.digit) {
                '0' -> cell.setOnLongClickListener {
                    dialInput.append('+'); renderDialInput(); true
                }
                '1' -> cell.setOnLongClickListener {
                    try {
                        getSystemService(TelecomManager::class.java)
                            .placeCall(Uri.fromParts("voicemail", "", null), Bundle())
                    } catch (e: Exception) {
                        Toast.makeText(this, "No voicemail configured", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> {}
            }
            grid.addView(cell)
        }

        btnBackspace.setOnClickListener {
            if (dialInput.isNotEmpty()) dialInput.deleteCharAt(dialInput.length - 1)
            renderDialInput()
        }
        btnBackspace.setOnLongClickListener {
            dialInput.clear(); renderDialInput(); true
        }

        findViewById<View>(R.id.btnDial).setOnClickListener {
            val n = dialInput.toString()
            if (n.isNotEmpty()) confirmCall(n)
        }
        renderDialInput()
    }

    private fun renderDialInput() {
        val raw = dialInput.toString()
        numberDisplay.text =
            if (raw.all { it.isDigit() || it == '+' })
                PhoneNumberUtils.formatNumber(raw, Locale.getDefault().country) ?: raw
            else raw
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
            currentTab == 0 && (keypadCollapsed || dialInput.isNotEmpty())
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
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(4))
        })
        val sheetAdapter = RecentsAdapter(expandable = false, onCall = {
            dialog.dismiss()
            confirmCall(it, title)
        })
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sheetAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(440)))
        sheetAdapter.items = buildRecentsItems(history)
        dialog.setContentView(root)
        dialog.show()
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
        class Group(val e: ContactsRepo.CallEntry, var count: Int)

        val merged = ArrayList<Group>()
        for (e in log) {
            val last = merged.lastOrNull()
            if (last != null && last.e.type == e.type &&
                last.e.number.filter { it.isDigit() } == e.number.filter { it.isDigit() } &&
                dayLabel(last.e.date) == dayLabel(e.date)
            ) {
                last.count++
            } else {
                merged.add(Group(e, 1))
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
                val bg = when {
                    j - i == 1 -> R.drawable.bg_group_single
                    k == i -> R.drawable.bg_group_top
                    k == j - 1 -> R.drawable.bg_group_bottom
                    else -> R.drawable.bg_group_mid
                }
                items.add(
                    RecentsAdapter.Item.Entry(
                        title = name,
                        count = g.count,
                        time = timeFmt.format(Date(g.e.date)),
                        type = g.e.type,
                        number = g.e.number,
                        duration = g.e.duration,
                        bg = bg,
                        divider = k < j - 1
                    )
                )
            }
            i = j
        }
        return items
    }

    private fun renderRecents() {
        recentsAdapter.items = buildRecentsItems(callLog)
        val hasPerm = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        recentsEmpty.visibility = if (callLog.isEmpty()) View.VISIBLE else View.GONE
        recentsEmpty.text = if (hasPerm) "No calls yet" else "Call log permission needed — grant it in the Gate tab"
    }

    // ---------------- Contacts ----------------

    private fun setupContacts() {
        contactsEmpty = findViewById(R.id.contactsEmpty)
        contactSearch = findViewById(R.id.contactSearch)
        contactsAdapter = RowAdapter { row ->
            showContactSheet(row.payload as ContactsRepo.Contact)
        }
        findViewById<RecyclerView>(R.id.contactsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(12)
        }
        contactSearch.doAfterTextChanged { renderContacts() }
    }

    private fun renderContacts() {
        val q = contactSearch.text.toString().trim().lowercase()
        val qDigits = q.filter { it.isDigit() }
        val list = ContactsRepo.contacts.filter { c ->
            q.isEmpty() ||
                c.name.lowercase().contains(q) ||
                (qDigits.length >= 3 && c.numbers.any { it.digits.contains(qDigits) })
        }
        contactsAdapter.rows = list.map { c ->
            RowAdapter.Row(
                title = c.name,
                subtitle = c.numbers.joinToString("  ·  ") { fmt(it.number) },
                meta = if (c.starred) "★" else "",
                avatarSeed = c.name,
                payload = c,
                metaColor = 0xFFFFC107.toInt()
            )
        }
        val hasPerm = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        contactsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        contactsEmpty.text = if (hasPerm) "No contacts" else "Contacts permission needed — grant it in the Gate tab"
    }

    // ---------------- Gate / settings ----------------

    private fun setupGate() {
        statusText = findViewById(R.id.statusText)
        logEmpty = findViewById(R.id.logEmpty)
        logAdapter = GateLogAdapter()
        findViewById<RecyclerView>(R.id.logList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            // The card lives in a ScrollView; let the list consume its own drags
            // so the page doesn't steal them mid-scroll.
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
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
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setContactName(this, editContact.text.toString().trim())
            val code = editCode.text.toString().trim().filter { it.isDigit() || it == '#' || it == '*' }
            Prefs.setGateCode(this, code.ifEmpty { Prefs.DEFAULT_CODE })
            editCode.setText(Prefs.gateCode(this))
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refreshGate()
        }
        findViewById<Button>(R.id.btnRole).setOnClickListener {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else {
                Toast.makeText(this, "Already the default phone app", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
        }
        // btnFsi opens the Android 14+ full-screen-intent permission page
        findViewById<Button>(R.id.btnFsi).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings → Apps → Call Assist → Notifications", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refreshGate() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLog(this)
            refreshGate()
        }
    }

    private fun refreshGate() {
        val rm = getSystemService(RoleManager::class.java)
        val isDialer = rm.isRoleHeld(RoleManager.ROLE_DIALER)
        val contacts = has(Manifest.permission.READ_CONTACTS)
        val callLogPerm = has(Manifest.permission.READ_CALL_LOG)
        val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)

        val sb = SpannableStringBuilder()
        fun line(ok: Boolean, text: String) {
            val start = sb.length
            sb.append("●  ")
            sb.setSpan(
                ForegroundColorSpan(if (ok) 0xFF26B858.toInt() else 0xFFF4523B.toInt()),
                start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(text).append('\n')
        }
        line(isDialer, if (isDialer) "Default phone app" else "Not the default phone app — automation off")
        line(contacts, if (contacts) "Contacts permission" else "Contacts permission missing")
        line(callLogPerm, if (callLogPerm) "Call log permission" else "Call log permission missing (Recents)")
        line(notif, if (notif) "Notifications" else "Notification permission missing")
        val fsi = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        line(
            fsi,
            if (fsi) "Full-screen call alerts"
            else "Full-screen call alerts blocked — calls show as a banner only"
        )
        val battery = getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        line(
            battery,
            if (battery) "Battery optimization exempt"
            else "Battery optimization active — exempt it in Settings → System"
        )
        findViewById<Button>(R.id.btnFsi).visibility = if (fsi) View.GONE else View.VISIBLE
        val on = Prefs.enabled(this)
        line(
            on,
            if (on) "Automation on for \"${Prefs.contactName(this)}\" → ${Prefs.gateCode(this)}"
            else "Automation is switched off"
        )
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        statusText.text = sb

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
        updateBackState()

        pendingReload?.let { navBar.removeCallbacks(it) }
        if (idx == 1) {
            val r = Runnable { pendingReload = null; reloadData() }
            pendingReload = r
            navBar.postDelayed(r, TAB_SETTLE_MS)
        }
    }

    private fun reloadData() {
        if (bg.isShutdown) return
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
        // Only worth showing when the user pinned a specific SIM.
        view.findViewById<TextView>(R.id.dlgSim).apply {
            if (Prefs.simMode(this@MainActivity) == Prefs.SIM_FIXED) {
                text = "via ${Prefs.simLabel(this@MainActivity)}"
                visibility = View.VISIBLE
            }
        }
        view.findViewById<TextView>(R.id.dlgCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.dlgCall).setOnClickListener {
            dialog.dismiss()
            placeCall(number)
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

    private fun placeCall(number: String, account: PhoneAccountHandle? = null) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        val tm = getSystemService(TelecomManager::class.java)
        val accounts = SimUtil.accounts(this)
        val chosen = account ?: when (Prefs.simMode(this)) {
            Prefs.SIM_FIXED -> accounts.firstOrNull { it.id == Prefs.simId(this) }
            Prefs.SIM_ASK -> {
                if (accounts.size > 1) {
                    val labels = accounts.mapIndexed { i, h -> SimUtil.label(this, h, i) }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Call with")
                        .setItems(labels) { _, i -> placeCall(number, accounts[i]) }
                        .show()
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

    private fun showContactSheet(contact: ContactsRepo.Contact) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_contact, null)
        dialog.setContentView(v)

        v.findViewById<TextView>(R.id.sheetName).text = contact.name
        v.findViewById<TextView>(R.id.sheetStar).text = if (contact.starred) "★" else ""
        val av = v.findViewById<TextView>(R.id.sheetAvatar)
        av.text = Ui.initial(contact.name)
        av.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(contact.name))

        val container = v.findViewById<LinearLayout>(R.id.numbersContainer)
        for (n in contact.numbers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            (row.layoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )).also { lp ->
                (lp as LinearLayout.LayoutParams).topMargin = dp(6)
                row.layoutParams = lp
            }
            row.addView(TextView(this).apply {
                text = "${n.label}\n${fmt(n.number)}"
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(roundIconButton(R.drawable.ic_message, getColor(R.color.card2), getColor(R.color.accent)) {
                dialog.dismiss()
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${n.number}")))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "No messaging app found", Toast.LENGTH_SHORT).show()
                }
            })
            row.addView(roundIconButton(R.drawable.ic_phone, getColor(R.color.green), 0xFFFFFFFF.toInt()) {
                dialog.dismiss()
                confirmCall(n.number, contact.name)
            })
            container.addView(row)
        }

        v.findViewById<Button>(R.id.btnEditContact).setOnClickListener {
            dialog.dismiss()
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
        v.findViewById<Button>(R.id.btnBlockContact).setOnClickListener {
            dialog.dismiss()
            if (contact.numbers.size == 1) {
                confirmBlock(contact.numbers[0].number)
            } else {
                val items = contact.numbers.map { fmt(it.number) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Block which number?")
                    .setItems(items) { _, i -> confirmBlock(contact.numbers[i].number) }
                    .show()
            }
        }
        dialog.show()
    }

    private fun roundIconButton(
        iconRes: Int,
        bgColor: Int,
        iconColor: Int,
        onClick: () -> Unit
    ): ImageButton = ImageButton(this).apply {
        setImageResource(iconRes)
        setBackgroundResource(R.drawable.bg_circle)
        backgroundTintList = ColorStateList.valueOf(bgColor)
        imageTintList = ColorStateList.valueOf(iconColor)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun confirmBlock(number: String) {
        AlertDialog.Builder(this)
            .setTitle("Block ${fmt(number)}?")
            .setMessage("Calls from this number will be rejected system-wide. You can unblock it in Settings → Blocked numbers.")
            .setPositiveButton("Block") { _, _ -> BlockedNumbersActivity.block(this, number) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fmt(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
