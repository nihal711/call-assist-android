package dev.nihal.callassist

import android.telephony.PhoneNumberUtils
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import java.util.Locale

/**
 * The keypad's number display. The EditText is the source of truth for the
 * dialled number; edits happen in "raw" (separator-free) space so the cursor
 * survives reformatting, and anything that lands by paste or hardware keys is
 * re-normalised the same way. [onChanged] fires after every change.
 */
class DialField(private val edit: EditText, private val onChanged: () -> Unit) {

    /** True while [set] is writing to the field itself. */
    private var rendering = false

    init {
        edit.showSoftInputOnFocus = false
        edit.doAfterTextChanged { s ->
            if (rendering) return@doAfterTextChanged
            val text = s?.toString().orEmpty()
            set(PhoneNumberUtils.stripSeparators(text), rawCursorAt(text, edit.selectionStart))
        }
    }

    /** Dialable chars (digits, plus, star, hash) currently in the display, formatting stripped. */
    fun raw(): String = PhoneNumberUtils.stripSeparators(edit.text.toString())

    /** Number of dialable chars before [pos] in the formatted [text]. */
    private fun rawCursorAt(text: CharSequence, pos: Int): Int {
        var n = 0
        for (i in 0 until pos.coerceIn(0, text.length)) if (PhoneNumberUtils.isNonSeparator(text[i])) n++
        return n
    }

    fun insert(c: Char) {
        val raw = raw()
        val text = edit.text
        val start = rawCursorAt(text, edit.selectionStart.takeIf { it >= 0 } ?: text.length)
        val end = rawCursorAt(text, edit.selectionEnd.takeIf { it >= 0 } ?: text.length)
        set(raw.substring(0, start) + c + raw.substring(end), start + 1)
    }

    fun backspace() {
        val raw = raw()
        if (raw.isEmpty()) return
        val text = edit.text
        var start = rawCursorAt(text, edit.selectionStart.takeIf { it >= 0 } ?: text.length)
        val end = rawCursorAt(text, edit.selectionEnd.takeIf { it >= 0 } ?: text.length)
        if (start == end) {
            if (start == 0) return
            start--
        }
        set(raw.substring(0, start) + raw.substring(end), start)
    }

    /** Replaces the number with [raw], placing the cursor after [rawCursor] dialable chars (default: end). */
    fun set(raw: String, rawCursor: Int = raw.length) {
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
        rendering = true
        edit.setText(formatted)
        edit.setSelection(pos.coerceIn(0, formatted.length))
        rendering = false
        onChanged()
    }
}
