package dev.nihal.callassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnTheme = findViewById<Button>(R.id.btnTheme)
        btnTheme.setOnClickListener { showThemePicker(btnTheme) }
        btnTheme.text = "Theme: ${themeLabel()}"

        val switchConfirm = findViewById<Switch>(R.id.switchConfirm)
        switchConfirm.isChecked = Prefs.confirmCall(this)
        switchConfirm.setOnCheckedChangeListener { _, checked ->
            Prefs.setConfirmCall(this, checked)
        }

        // Nothing to choose on a single-SIM phone, so the row only appears for dual SIM.
        val btnSim = findViewById<Button>(R.id.btnSim)
        btnSim.visibility = if (SimUtil.isDual(this)) View.VISIBLE else View.GONE
        btnSim.text = "Call with: ${SimUtil.modeLabel(this)}"
        btnSim.setOnClickListener {
            SimUtil.showPicker(this) { btnSim.text = "Call with: ${SimUtil.modeLabel(this)}" }
        }

        findViewById<Button>(R.id.btnReplies).setOnClickListener { showReplies() }

        val switchIdd = findViewById<Switch>(R.id.switchIdd)
        val btnIddCode = findViewById<Button>(R.id.btnIddCode)
        switchIdd.isChecked = Prefs.iddEnabled(this)
        btnIddCode.isEnabled = switchIdd.isChecked
        refreshIddHint()
        switchIdd.setOnCheckedChangeListener { _, checked ->
            Prefs.setIddEnabled(this, checked)
            btnIddCode.isEnabled = checked
            refreshIddHint()
        }
        btnIddCode.text = "IDD code: ${Prefs.iddCode(this)}"
        btnIddCode.setOnClickListener { editIddCode(btnIddCode) }

        val btnRetryWait = findViewById<Button>(R.id.btnRetryWait)
        btnRetryWait.text = "Wait for gate: ${Prefs.retryWaitSeconds(this)}s"
        btnRetryWait.setOnClickListener {
            pick(
                title = "Wait for gate",
                choices = Prefs.RETRY_WAIT_CHOICES,
                labels = Prefs.RETRY_WAIT_CHOICES.map { s ->
                    if (s == Prefs.DEFAULT_RETRY_WAIT_S) "${s}s (default)" else "${s}s"
                },
                current = Prefs.retryWaitSeconds(this)
            ) { chosen ->
                Prefs.setRetryWaitSeconds(this, chosen)
                btnRetryWait.text = "Wait for gate: ${chosen}s"
            }
        }

        val btnMaxAttempts = findViewById<Button>(R.id.btnMaxAttempts)
        btnMaxAttempts.text = "Code attempts: ${Prefs.maxAttempts(this)}"
        btnMaxAttempts.setOnClickListener {
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
                btnMaxAttempts.text = "Code attempts: $chosen"
            }
        }

        val switchTones = findViewById<Switch>(R.id.switchTones)
        switchTones.isChecked = Prefs.keyTones(this)
        switchTones.setOnCheckedChangeListener { _, checked -> Prefs.setKeyTones(this, checked) }

        val switchHaptics = findViewById<Switch>(R.id.switchHaptics)
        switchHaptics.isChecked = Prefs.keyHaptics(this)
        switchHaptics.setOnCheckedChangeListener { _, checked -> Prefs.setKeyHaptics(this, checked) }

        val btnCrash = findViewById<Button>(R.id.btnCrash)
        btnCrash.visibility = if (CrashLog.read(this) != null) View.VISIBLE else View.GONE
        btnCrash.setOnClickListener { showCrashReport(btnCrash) }

        findViewById<Button>(R.id.btnBlocked).setOnClickListener {
            startActivity(Intent(this, BlockedNumbersActivity::class.java))
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
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
        findViewById<Button>(R.id.btnBattery).isEnabled = !ignoring
        findViewById<TextView>(R.id.batteryHint).text =
            if (ignoring) "✓ Already exempt — Samsung's battery manager won't interfere."
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

    private fun showThemePicker(btn: Button) {
        val modes = arrayOf("dark", "light", "system")
        val labels = arrayOf("Dark", "Light", "Follow device")
        val current = modes.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        Sheet(this)
            .title("Theme")
            .singleChoice(labels.toList(), current) { which ->
                Prefs.setThemeMode(this, modes[which])
                CallAssistApp.applyTheme(this)
                btn.text = "Theme: ${themeLabel()}"
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

    private fun editIddCode(btn: Button) {
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
                btn.text = "IDD code: ${Prefs.iddCode(this)}"
                refreshIddHint()
            }
            .show()
    }

    /** The stack trace of the last crash, with Copy so it can be pasted into a bug report. */
    private fun showCrashReport(btn: Button) {
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
