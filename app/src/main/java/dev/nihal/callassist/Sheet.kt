package dev.nihal.callassist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheet used wherever the app used to pop a stock
 * AlertDialog: rounded panel rising from the bottom, bold title, optional
 * message / custom view / list, and a split Cancel | OK button row.
 *
 *   Sheet(ctx).title("Filter calls")
 *       .singleChoice(labels, current) { i -> ... }
 *       .negative().positive("OK").show()
 */
class Sheet(private val ctx: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var custom: View? = null
    private var positiveText: CharSequence? = null
    private var positiveDestructive = false
    private var positiveClick: (() -> Unit)? = null
    private var negativeText: CharSequence? = null
    private var negativeClick: (() -> Unit)? = null

    private var itemLabels: List<CharSequence>? = null
    private var itemIcons: List<View?>? = null
    private var itemClick: ((Int) -> Unit)? = null
    private var choiceLabels: List<CharSequence>? = null
    private var choiceIcons: List<View?>? = null
    private var choiceChecked = -1
    private var choiceClick: ((Int) -> Unit)? = null

    fun title(t: CharSequence) = apply { title = t }
    fun message(m: CharSequence) = apply { message = m }
    fun view(v: View) = apply { custom = v }

    /**
     * Plain rows; tapping one dismisses the sheet and reports its index.
     * [icons] are optional per-row views (e.g. a SIM badge) shown before the label.
     */
    fun items(labels: List<CharSequence>, icons: List<View?>? = null, onPick: (Int) -> Unit) = apply {
        itemLabels = labels; itemIcons = icons; itemClick = onPick
    }

    /**
     * Radio rows. With a [positive] button the pick is reported when it is
     * tapped; without one, tapping a row reports and dismisses immediately.
     */
    fun singleChoice(
        labels: List<CharSequence>,
        checked: Int,
        icons: List<View?>? = null,
        onPick: (Int) -> Unit
    ) = apply {
        choiceLabels = labels; choiceChecked = checked; choiceIcons = icons; choiceClick = onPick
    }

    fun positive(text: CharSequence, destructive: Boolean = false, onClick: (() -> Unit)? = null) = apply {
        positiveText = text; positiveDestructive = destructive; positiveClick = onClick
    }

    fun negative(text: CharSequence = "Cancel", onClick: (() -> Unit)? = null) = apply {
        negativeText = text; negativeClick = onClick
    }

    fun show(): BottomSheetDialog {
        val dialog = BottomSheetDialog(ctx, R.style.AppSheet)
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val accent = ContextCompat.getColor(ctx, R.color.accent)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(20), dp(10), dp(20), dp(14))
        }
        // Drag handle: the standard cue that the panel is dismissible.
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            })
        title?.let {
            root.addView(TextView(ctx).apply {
                text = it
                textSize = 20f
                setTextColor(primary)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, dp(8), dp(6))
            })
        }
        message?.let {
            root.addView(TextView(ctx).apply {
                text = it
                textSize = 15f
                setTextColor(secondary)
                setLineSpacing(0f, 1.15f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
        }
        custom?.let { v ->
            root.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); leftMargin = dp(8); rightMargin = dp(8) })
        }

        itemLabels?.let { labels ->
            for ((i, label) in labels.withIndex()) {
                root.addView(row(label, primary, null, itemIcons?.getOrNull(i)).apply {
                    setOnClickListener { dialog.dismiss(); itemClick?.invoke(i) }
                })
            }
        }

        var picked = choiceChecked
        choiceLabels?.let { labels ->
            val radios = ArrayList<ImageView>()
            fun paint() {
                radios.forEachIndexed { i, iv ->
                    val on = i == picked
                    iv.setImageResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
                    iv.imageTintList = ColorStateList.valueOf(if (on) accent else secondary)
                }
            }
            for ((i, label) in labels.withIndex()) {
                val radio = ImageView(ctx)
                radios.add(radio)
                root.addView(row(label, primary, radio, choiceIcons?.getOrNull(i)).apply {
                    setOnClickListener {
                        picked = i
                        paint()
                        if (positiveText == null) {
                            dialog.dismiss()
                            choiceClick?.invoke(i)
                        }
                    }
                })
            }
            paint()
        }

        if (positiveText != null || negativeText != null) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun button(text: CharSequence, color: Int, bold: Boolean, onClick: () -> Unit) =
                TextView(ctx).apply {
                    this.text = text
                    textSize = 17f
                    setTextColor(color)
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_sheet_button)
                    setOnClickListener { onClick() }
                    Ui.pressable(this)
                }
            val lp = LinearLayout.LayoutParams(0, dp(50), 1f)
            negativeText?.let {
                bar.addView(button(it, primary, false) { dialog.dismiss(); negativeClick?.invoke() }, lp)
            }
            if (positiveText != null && negativeText != null) {
                bar.addView(View(ctx).apply {
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.trackBg))
                }, LinearLayout.LayoutParams(dp(1), dp(22)))
            }
            positiveText?.let {
                val color = if (positiveDestructive) ContextCompat.getColor(ctx, R.color.red) else primary
                bar.addView(button(it, color, true) {
                    dialog.dismiss()
                    if (choiceLabels != null) choiceClick?.invoke(picked)
                    positiveClick?.invoke()
                }, lp)
            }
            root.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }

        dialog.setContentView(root)
        // Long lists should open fully rather than at a half-height peek.
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        return dialog
    }

    private fun row(label: CharSequence, color: Int, lead: ImageView?, icon: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.bg_sheet_row)
            lead?.let { iv ->
                addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) })
            }
            icon?.let { v ->
                val lp = (v.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(dp(22), dp(22))
                addView(v, lp.apply { marginEnd = dp(14) })
            }
            addView(TextView(ctx).apply {
                text = label
                textSize = 17f
                setTextColor(color)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
