package dev.nihal.callassist

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.format.DateUtils
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    private lateinit var recentsAdapter: RowAdapter
    private lateinit var recentsEmpty: TextView
    private lateinit var contactsAdapter: RowAdapter
    private lateinit var contactsEmpty: TextView
    private lateinit var contactSearch: EditText
    private var callLog: List<ContactsRepo.CallEntry> = emptyList()

    // Gate
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private lateinit var pages: Map<Int, View>
    private lateinit var bottomNav: BottomNavigationView

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

        bottomNav = findViewById(R.id.bottomNav)
        pages = mapOf(
            R.id.navKeypad to findViewById(R.id.pageKeypad),
            R.id.navRecents to findViewById(R.id.pageRecents),
            R.id.navContacts to findViewById(R.id.pageContacts),
            R.id.navGate to findViewById(R.id.pageGate)
        )
        bottomNav.setOnItemSelectedListener { item ->
            pages.forEach { (id, v) -> v.visibility = if (id == item.itemId) View.VISIBLE else View.GONE }
            if (item.itemId == R.id.navRecents) reloadData()
            true
        }

        setupKeypad()
        setupRecents()
        setupContacts()
        setupGate()

        // First run: take the user to setup until the app is the default dialer.
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) bottomNav.selectedItemId = R.id.navGate
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
        bottomNav.selectedItemId = R.id.navKeypad
        renderDialInput()
    }

    override fun onResume() {
        super.onResume()
        refreshGate()
        reloadData()
    }

    override fun onDestroy() {
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

        suggestionsAdapter = RowAdapter { row -> confirmCall(row.payload as String, row.title) }
        suggestionsList.layoutManager = LinearLayoutManager(this)
        suggestionsList.adapter = suggestionsAdapter

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
                ).apply { width = 0; height = dp(64) }
            }
            cell.addView(TextView(this).apply {
                text = k.digit.toString()
                textSize = 26f
                gravity = Gravity.CENTER
            })
            if (k.letters.isNotEmpty()) cell.addView(TextView(this).apply {
                text = k.letters
                textSize = 10f
                alpha = 0.6f
                gravity = Gravity.CENTER
            })
            cell.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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

        val matches = ContactsRepo.search(raw)
        suggestionsAdapter.rows = matches.map {
            RowAdapter.Row(
                title = it.contact.name,
                subtitle = "${it.number.label} · ${fmt(it.number.number)}",
                meta = "",
                avatarSeed = it.contact.name,
                payload = it.number.number
            )
        }
        val showList = matches.isNotEmpty()
        suggestionsList.visibility = if (showList) View.VISIBLE else View.GONE
        suggestionsEmpty.visibility = if (showList) View.GONE else View.VISIBLE
        if (raw.isNotEmpty() && matches.isEmpty()) suggestionsEmpty.text = "No matches"
        else if (raw.isEmpty()) suggestionsEmpty.text = "Type a number or a name (T9)"
    }

    private fun playKeyTone(digit: Char) {
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
        recentsAdapter = RowAdapter { row -> confirmCall(row.payload as String, row.title) }
        findViewById<RecyclerView>(R.id.recentsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recentsAdapter
        }
    }

    private fun renderRecents() {
        val red = 0xFFE05353.toInt()
        val green = 0xFF4CAF50.toInt()
        val blue = 0xFF64A5F5.toInt()
        recentsAdapter.rows = callLog.map { e ->
            val name = e.name?.takeIf { it.isNotBlank() }
                ?: ContactsRepo.lookupNameCached(e.number)
            val (arrow, color) = when (e.type) {
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE,
                CallLog.Calls.BLOCKED_TYPE -> "✕ missed" to red
                CallLog.Calls.OUTGOING_TYPE -> "↗ out" to blue
                else -> "↙ in" to green
            }
            RowAdapter.Row(
                title = name ?: fmt(e.number),
                subtitle = if (name != null) fmt(e.number) else "",
                meta = "$arrow · ${DateUtils.getRelativeTimeSpanString(e.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)}",
                avatarSeed = name ?: e.number,
                payload = e.number,
                metaColor = color
            )
        }
        val hasPerm = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        recentsEmpty.visibility = if (callLog.isEmpty()) View.VISIBLE else View.GONE
        recentsEmpty.text = if (hasPerm) "No calls yet" else "Call log permission needed — grant it in the Gate tab"
    }

    // ---------------- Contacts ----------------

    private fun setupContacts() {
        contactsEmpty = findViewById(R.id.contactsEmpty)
        contactSearch = findViewById(R.id.contactSearch)
        contactsAdapter = RowAdapter { row ->
            val contact = row.payload as ContactsRepo.Contact
            if (contact.numbers.size == 1) {
                confirmCall(contact.numbers[0].number, contact.name)
            } else {
                val items = contact.numbers.map { "${it.label}: ${fmt(it.number)}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(contact.name)
                    .setItems(items) { _, i -> confirmCall(contact.numbers[i].number, contact.name) }
                    .show()
            }
        }
        findViewById<RecyclerView>(R.id.contactsList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactsAdapter
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
        logText = findViewById(R.id.logText)
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
        findViewById<Button>(R.id.btnSim).setOnClickListener { showSimPicker() }
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
        findViewById<Button>(R.id.btnFsi).visibility = if (fsi) View.GONE else View.VISIBLE
        val on = Prefs.enabled(this)
        line(
            on,
            if (on) "Automation on for \"${Prefs.contactName(this)}\" → ${Prefs.gateCode(this)}"
            else "Automation is switched off"
        )
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        statusText.text = sb

        findViewById<Button>(R.id.btnSim).text = "Default SIM: ${Prefs.simLabel(this)}"
        logText.text = Prefs.readLog(this)
    }

    private fun callAccounts(tm: TelecomManager): List<PhoneAccountHandle> =
        try {
            tm.callCapablePhoneAccounts
        } catch (_: SecurityException) {
            emptyList()
        }

    private fun accountLabel(tm: TelecomManager, h: PhoneAccountHandle, i: Int): String =
        try {
            val acct = tm.getPhoneAccount(h)
            val label = acct?.label?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${i + 1}"
            val addr = acct?.address?.schemeSpecificPart
            if (!addr.isNullOrBlank() && !label.contains(addr)) "$label (${fmt(addr)})" else label
        } catch (_: Exception) {
            "SIM ${i + 1}"
        }

    private fun showSimPicker() {
        val tm = getSystemService(TelecomManager::class.java)
        val accounts = callAccounts(tm)
        val labels = mutableListOf("System default", "Ask every time")
        accounts.forEachIndexed { i, h -> labels.add(accountLabel(tm, h, i)) }
        AlertDialog.Builder(this)
            .setTitle("Default SIM for calls")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> Prefs.setSim(this, Prefs.SIM_SYSTEM, "", "System default")
                    1 -> Prefs.setSim(this, Prefs.SIM_ASK, "", "Ask every time")
                    else -> {
                        val h = accounts[which - 2]
                        Prefs.setSim(this, Prefs.SIM_FIXED, h.id, labels[which])
                    }
                }
                refreshGate()
            }
            .show()
    }

    // ---------------- Shared ----------------

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
        val display = name?.takeIf { it.isNotBlank() }
            ?: ContactsRepo.lookupNameCached(number)
            ?: fmt(number)
        AlertDialog.Builder(this)
            .setTitle(display)
            .setMessage("Call ${fmt(number)}?")
            .setPositiveButton("Call") { _, _ -> placeCall(number) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeCall(number: String, account: PhoneAccountHandle? = null) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }
        val tm = getSystemService(TelecomManager::class.java)
        val accounts = callAccounts(tm)
        val chosen = account ?: when (Prefs.simMode(this)) {
            Prefs.SIM_FIXED -> accounts.firstOrNull { it.id == Prefs.simId(this) }
            Prefs.SIM_ASK -> {
                if (accounts.size > 1) {
                    val labels = accounts.mapIndexed { i, h -> accountLabel(tm, h, i) }.toTypedArray()
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

    private fun fmt(number: String): String =
        PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
