package dev.nihal.callassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private fun value(id: Int, text: String) {
        findViewById<TextView>(id).text = text
    }

    private fun rowEnabled(id: Int, enabled: Boolean) {
        findViewById<View>(id).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.45f }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            (findViewById<View>(R.id.settingsColumn).layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = dp(640)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }

        value(R.id.valTheme, themeLabel())
        findViewById<View>(R.id.rowTheme).setOnClickListener { showThemePicker() }

        val switchConfirm = findViewById<CompoundButton>(R.id.switchConfirm)
        switchConfirm.isChecked = Prefs.confirmCall(this)
        switchConfirm.setOnCheckedChangeListener { _, checked ->
            Prefs.setConfirmCall(this, checked)
        }

        // Nothing to choose on a single-SIM phone, so the row only appears for dual SIM.
        val rowSim = findViewById<View>(R.id.rowSim)
        rowSim.visibility = if (SimUtil.isDual(this)) View.VISIBLE else View.GONE
        value(R.id.valSim, SimUtil.modeLabel(this))
        rowSim.setOnClickListener {
            SimUtil.showPicker(this) { value(R.id.valSim, SimUtil.modeLabel(this)) }
        }

        findViewById<View>(R.id.rowReplies).setOnClickListener { showReplies() }

        val switchIdd = findViewById<CompoundButton>(R.id.switchIdd)
        switchIdd.isChecked = Prefs.iddEnabled(this)
        rowEnabled(R.id.rowIddCode, switchIdd.isChecked)
        refreshIddHint()
        switchIdd.setOnCheckedChangeListener { _, checked ->
            Prefs.setIddEnabled(this, checked)
            rowEnabled(R.id.rowIddCode, checked)
            refreshIddHint()
        }
        value(R.id.valIddCode, Prefs.iddCode(this))
        findViewById<View>(R.id.rowIddCode).setOnClickListener { editIddCode() }

        value(R.id.valRetryWait, "${Prefs.retryWaitSeconds(this)}s")
        findViewById<View>(R.id.rowRetryWait).setOnClickListener {
            pick(
                title = "Wait for gate",
                choices = Prefs.RETRY_WAIT_CHOICES,
                labels = Prefs.RETRY_WAIT_CHOICES.map { s ->
                    if (s == Prefs.DEFAULT_RETRY_WAIT_S) "${s}s (default)" else "${s}s"
                },
                current = Prefs.retryWaitSeconds(this)
            ) { chosen ->
                Prefs.setRetryWaitSeconds(this, chosen)
                value(R.id.valRetryWait, "${chosen}s")
            }
        }

        value(R.id.valMaxAttempts, "${Prefs.maxAttempts(this)}")
        findViewById<View>(R.id.rowMaxAttempts).setOnClickListener {
            pick(
                title = "Code attempts",
                choices = Prefs.MAX_ATTEMPT_CHOICES,
                labels = Prefs.MAX_ATTEMPT_CHOICES.map { n ->
                    val base = if (n == 1) "1 (no retry)" else "$n"
                    if (n == Prefs.DEFAULT_MAX_ATTEMPTS) "$base — default" else base
                },
                current = Prefs.maxAttempts(this)
            ) { chosen ->
                Prefs.setMaxAttempts(this, chosen)
                value(R.id.valMaxAttempts, "$chosen")
            }
        }

        val switchTones = findViewById<CompoundButton>(R.id.switchTones)
        switchTones.isChecked = Prefs.keyTones(this)
        switchTones.setOnCheckedChangeListener { _, checked -> Prefs.setKeyTones(this, checked) }

        val switchHaptics = findViewById<CompoundButton>(R.id.switchHaptics)
        switchHaptics.isChecked = Prefs.keyHaptics(this)
        switchHaptics.setOnCheckedChangeListener { _, checked -> Prefs.setKeyHaptics(this, checked) }

        val rowCrash = findViewById<View>(R.id.rowCrash)
        rowCrash.visibility = if (CrashLog.read(this) != null) View.VISIBLE else View.GONE
        rowCrash.setOnClickListener { showCrashReport(rowCrash) }

        val switchBlockUnknown = findViewById<CompoundButton>(R.id.switchBlockUnknown)
        switchBlockUnknown.isChecked = Prefs.blockUnknown(this)
        switchBlockUnknown.setOnCheckedChangeListener { _, checked -> Prefs.setBlockUnknown(this, checked) }

        findViewById<View>(R.id.rowBlocked).setOnClickListener {
            startActivity(Intent(this, BlockedNumbersActivity::class.java))
        }

        findViewById<View>(R.id.rowBattery).setOnClickListener {
            try {
                @Suppress("BatteryLife")
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        findViewById<TextView>(R.id.versionText).text = try {
            "Call Assist v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "Call Assist"
        }
    }

    override fun onResume() {
        super.onResume()
        val ignoring = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        rowEnabled(R.id.rowBattery, !ignoring)
        value(R.id.valBattery, if (ignoring) "Exempt" else "")
        findViewById<TextView>(R.id.batteryHint).text =
            if (ignoring) "Already exempt — Samsung's battery manager won't interfere."
            else "Keeps Samsung's battery manager from ever interfering with gate automation."
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Single-choice dialog over a fixed set of numbers. */
    private fun pick(
        title: String,
        choices: IntArray,
        labels: List<String>,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        Sheet(this)
            .title(title)
            .singleChoice(labels, choices.indexOf(current)) { onPick(choices[it]) }
            .negative()
            .show()
    }

    private fun themeLabel(): String = when (Prefs.themeMode(this)) {
        "light" -> "Light"
        "system" -> "Follow device"
        else -> "Dark"
    }

    private fun showThemePicker() {
        val modes = arrayOf("dark", "light", "system")
        val labels = arrayOf("Dark", "Light", "Follow device")
        val current = modes.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        Sheet(this)
            .title("Theme")
            .singleChoice(labels.toList(), current) { which ->
                Prefs.setThemeMode(this, modes[which])
                CallAssistApp.applyTheme(this)
                value(R.id.valTheme, themeLabel())
            }
            .negative()
            .show()
    }

    private fun refreshIddHint() {
        val code = Prefs.iddCode(this)
        findViewById<TextView>(R.id.iddHint).text =
            if (Prefs.iddEnabled(this))
                "Numbers opened from Chrome and other apps have their + replaced with $code (e.g. +60 16… becomes ${code}60 16…). Keypad and contacts are untouched."
            else
                "Numbers opened from Chrome and other apps are dialled as-is, with the + kept."
    }

    private fun editIddCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = Prefs.DEFAULT_IDD_CODE
            setText(Prefs.iddCode(this@SettingsActivity))
            setSelection(text.length)
        }
        Sheet(this)
            .title("IDD code")
            .message("Dialled in place of the + on numbers opened from other apps. Singtel: 019 (budget) or 001.")
            .view(input)
            .negative()
            .positive("Save") {
                Prefs.setIddCode(this, input.text.toString())
                value(R.id.valIddCode, Prefs.iddCode(this))
                refreshIddHint()
            }
            .show()
    }

    /** The stack trace of the last crash, with Copy so it can be pasted into a bug report. */
    private fun showCrashReport(btn: View) {
        val report = CrashLog.read(this) ?: run {
            btn.visibility = View.GONE
            return
        }
        val text = TextView(this).apply {
            this.text = report
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.textSecondary))
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val share = TextView(this).apply {
            this.text = "Share…"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_compact_tile)
            setPadding(0, dp(11), 0, dp(11))
            setOnClickListener {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Call Assist crash report")
                            .putExtra(Intent.EXTRA_TEXT, report),
                        "Share crash report"
                    )
                )
            }
        }
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(
                scroll,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    // Leave room for title, message and the Copy | Delete bar — a
                    // taller viewer pushed the buttons off the bottom of the sheet.
                    (resources.displayMetrics.heightPixels * 0.26f).toInt()
                )
            )
            addView(
                share,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
        }
        Sheet(this)
            .title("Last crash report")
            .message("Copy or share this so the bug can be fixed.")
            .view(body)
            .negative("Delete") {
                CrashLog.clear(this)
                btn.visibility = View.GONE
            }
            .positive("Copy") {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Call Assist crash report", report))
                android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showReplies() {
        val replies = Prefs.quickReplies(this)
        Sheet(this)
            .title("Quick reply messages")
            .items(replies.toList()) { editReply(it) }
            .negative("Close")
            .show()
    }

    private fun editReply(i: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(Prefs.quickReplies(this@SettingsActivity)[i])
            setSelection(text.length)
        }
        Sheet(this)
            .title("Edit reply ${i + 1}")
            .view(input)
            .negative()
            .positive("Save") { Prefs.setQuickReply(this, i, input.text.toString().trim()) }
            .show()
    }
}
