package dev.nihal.callassist

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

        val btnSim = findViewById<Button>(R.id.btnSim)
        btnSim.text = "Default SIM: ${Prefs.simLabel(this)}"
        btnSim.setOnClickListener {
            SimUtil.showPicker(this) { btnSim.text = "Default SIM: ${Prefs.simLabel(this)}" }
        }

        findViewById<Button>(R.id.btnReplies).setOnClickListener { showReplies() }

        findViewById<Button>(R.id.btnBlocked).setOnClickListener {
            startActivity(Intent(this, BlockedNumbersActivity::class.java))
        }

        findViewById<TextView>(R.id.versionText).text = try {
            "Call Assist v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) {
            "Call Assist"
        }
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
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.setThemeMode(this, modes[which])
                CallAssistApp.applyTheme(this)
                btn.text = "Theme: ${themeLabel()}"
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReplies() {
        val replies = Prefs.quickReplies(this)
        AlertDialog.Builder(this)
            .setTitle("Quick reply messages")
            .setItems(replies) { _, i -> editReply(i) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editReply(i: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(Prefs.quickReplies(this@SettingsActivity)[i])
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit reply ${i + 1}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setQuickReply(this, i, input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
