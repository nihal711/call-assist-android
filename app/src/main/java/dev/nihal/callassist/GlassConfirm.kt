package dev.nihal.callassist

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Glass confirmation dialog: the same centred panel as the call confirmation
 * (blurred backdrop, 26dp corners, capsule Cancel | Action buttons), used for
 * every yes/no decision. Pickers and text entry stay on [Sheet].
 *
 *   GlassConfirm(ctx).title("Delete call log?").message("…")
 *       .positive("Delete", destructive = true) { … }.show()
 */
class GlassConfirm(private val ctx: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var positiveText: CharSequence = "OK"
    private var destructive = false
    private var positiveClick: (() -> Unit)? = null
    private var negativeText: CharSequence = "Cancel"
    private var negativeClick: (() -> Unit)? = null

    fun title(t: CharSequence) = apply { title = t }
    fun message(m: CharSequence) = apply { message = m }
    fun positive(text: CharSequence, destructive: Boolean = false, onClick: (() -> Unit)? = null) =
        apply { positiveText = text; this.destructive = destructive; positiveClick = onClick }
    fun negative(text: CharSequence = "Cancel", onClick: (() -> Unit)? = null) =
        apply { negativeText = text; negativeClick = onClick }

    fun show(): AlertDialog {
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            setPaddingRelative(dp(28), dp(32), dp(28), dp(24))
        }
        title?.let {
            panel.addView(TextView(ctx).apply {
                text = it
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
                gravity = Gravity.CENTER
            })
        }
        message?.let {
            panel.addView(TextView(ctx).apply {
                text = it
                textSize = 15f
                setTextColor(secondary)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }

        val dialog = AlertDialog.Builder(ctx, R.style.GlassDialog).setView(panel).create()

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // 18sp bold is WCAG large text, so the white label needs 3:1 on the fill.
        fun button(text: CharSequence, bg: Int, color: Int, bold: Boolean, onClick: () -> Unit) =
            TextView(ctx).apply {
                this.text = text
                textSize = if (bold) 18f else 17f
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setTextColor(color)
                gravity = Gravity.CENTER
                minimumHeight = dp(52)
                setBackgroundResource(bg)
                setOnClickListener { onClick() }
                Ui.pressable(this)
            }
        bar.addView(
            button(negativeText, R.drawable.bg_dialog_cancel, primary, false) { dialog.dismiss(); negativeClick?.invoke() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        )
        bar.addView(
            button(
                positiveText,
                if (destructive) R.drawable.bg_dialog_destructive else R.drawable.bg_dialog_positive,
                0xFFFFFFFF.toInt(), true
            ) { dialog.dismiss(); positiveClick?.invoke() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        )
        panel.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        Glass.applyDialogBlur(dialog, panel)
        dialog.show()
        dialog.window?.setLayout(
            minOf((ctx.resources.displayMetrics.widthPixels * 0.86f).toInt(), dp(420)),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
