package dev.nihal.callassist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Floating pill nav bar: a translucent rounded bar with a
 * bubble that sits behind the selected tab. Dragging a finger across the bar
 * carries the bubble along; releasing snaps to (and selects) the nearest tab.
 */
class GlassNavBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /**
     * [iconRes] is the resting (outlined) icon; [activeIconRes] is swapped in
     * while the tab is selected. Pass only [iconRes] for a tab that keeps the
     * same glyph in both states.
     */
    data class TabSpec(
        val iconRes: Int,
        val label: String,
        val activeIconRes: Int = iconRes
    )

    var onTabSelected: ((Int) -> Unit)? = null

    // Decelerating ease-out: quick off the mark, settles softly — matches the
    // feel of the rest of the UI better than the default linear-ish interpolator.
    private val bubbleInterpolator = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)

    private val bubble = View(context)
    private val row = LinearLayout(context)
    private val icons = mutableListOf<ImageView>()
    private val badges = mutableListOf<TextView>()
    private val labels = mutableListOf<TextView>()
    private val specs = mutableListOf<TabSpec>()
    /** Currently applied icon per tab, so we only call setImageResource on a change. */
    private val shownIcons = mutableListOf<Int>()
    private var count = 0
    private var selected = 0
    private var dragging = false
    private var moved = false

    init {
        setBackgroundResource(R.drawable.bg_navbar)
        bubble.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.navBubble))
        }
        addView(bubble)
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setTabs(tabs: List<TabSpec>) {
        row.removeAllViews()
        icons.clear()
        badges.clear()
        labels.clear()
        specs.clear()
        specs.addAll(tabs)
        shownIcons.clear()
        count = tabs.size
        for (t in tabs) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val icon = ImageView(context).apply { setImageResource(t.iconRes) }
            shownIcons.add(t.iconRes)
            // Icon sits in a small frame so a count badge can hang off its corner.
            val iconBox = FrameLayout(context)
            iconBox.addView(icon, LayoutParams(dp(23), dp(23), Gravity.CENTER))
            val badge = TextView(context).apply {
                textSize = 9.5f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                minWidth = dp(15)
                setPadding(dp(4), 0, dp(4), 0)
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.red))
                    cornerRadius = dp(8).toFloat()
                }
                visibility = GONE
            }
            iconBox.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(15), Gravity.TOP or Gravity.END))
            badges.add(badge)
            item.addView(iconBox, LinearLayout.LayoutParams(dp(39), dp(27)))
            val label = TextView(context).apply label@{
                text = t.label
                textSize = 11.5f
                gravity = Gravity.CENTER
                includeFontPadding = false   // trims the ascent/descent slack above the text
                // Reserve the bold width up front so selecting a tab only
                // repaints the label instead of re-measuring the bar. Measure
                // against this TextView's own paint (this@apply inside the
                // inner block would resolve to the TextPaint, silently
                // measuring at its default size instead of the label's).
                val bold = TextPaint().apply {
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = this@label.textSize
                }
                minWidth = Math.ceil(bold.measureText(t.label).toDouble()).toInt()
            }
            item.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) }
            )
            row.addView(
                item,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            icons.add(icon)
            labels.add(label)
        }
        updateTints()
    }

    /** Red count on a tab's icon (missed calls on Recents); 0 hides it. */
    fun setBadge(i: Int, n: Int) {
        val b = badges.getOrNull(i) ?: return
        b.text = if (n > 9) "9+" else n.toString()
        b.visibility = if (n > 0) VISIBLE else GONE
    }

    private fun itemWidth() = if (count == 0) 0f else width.toFloat() / count
    // Bubble insets: 6dp all round. Any more at the sides and, on a narrow
    // display where a tab slot is ~65dp, the pill collapses into a circle that
    // only rings the icon; the bar itself is kept wide (see activity_main) so
    // the pill always spans icon + label.
    private fun bubbleW() = (itemWidth() - dp(12)).coerceAtLeast(0f)
    private fun slotX(i: Int) = i * itemWidth() + dp(6)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (count == 0) return
        // Requesting layout during a layout pass is dropped — defer so the
        // bubble is sized correctly on the very first frame.
        post {
            val bh = height - dp(12)
            bubble.layoutParams = LayoutParams(bubbleW().toInt(), bh).apply { topMargin = dp(6) }
            (bubble.background as GradientDrawable).cornerRadius = bh / 2f
            bubble.translationX = slotX(selected)
        }
    }

    fun select(i: Int, notify: Boolean = true) {
        if (count == 0) return
        val idx = i.coerceIn(0, count - 1)
        selected = idx
        if (width > 0) {
            bubble.animate()
                .translationX(slotX(idx))
                .setDuration(220)
                .setInterpolator(bubbleInterpolator)
                .withLayer()   // render the bubble into a hardware layer for the slide
                .start()
        }
        updateTints()
        if (notify) onTabSelected?.invoke(idx)
    }

    private fun updateTints() {
        val active = ContextCompat.getColor(context, R.color.textPrimary)
        val inactive = ContextCompat.getColor(context, R.color.textSecondary)
        for (i in icons.indices) {
            val c = if (i == selected) active else inactive
            icons[i].imageTintList = ColorStateList.valueOf(c)
            // Outlined at rest, solid while selected. Guarded because
            // setImageResource on an unchanged drawable still forces a
            // requestLayout of the bar mid-slide.
            val want = if (i == selected) specs[i].activeIconRes else specs[i].iconRes
            if (shownIcons[i] != want) {
                icons[i].setImageResource(want)
                shownIcons[i] = want
            }
            labels[i].setTextColor(c)
            // Toggling bold changes the text's measured width, which would
            // request a layout pass of the whole bar mid-animation. The labels
            // are already sized for bold (see setTabs), so this only repaints.
            labels[i].setTypeface(null, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (count == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                moved = true
                bubble.translationX = (e.x - bubbleW() / 2)
                    .coerceIn(dp(6).toFloat(), width - bubbleW() - dp(6))
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val idx = (e.x / itemWidth()).toInt().coerceIn(0, count - 1)
                if (idx != selected || moved) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                select(idx)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                select(selected, notify = false)
            }
        }
        return true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
