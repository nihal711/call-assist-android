package dev.nihal.callassist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
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

    data class TabSpec(val iconRes: Int, val label: String)

    var onTabSelected: ((Int) -> Unit)? = null

    private val bubble = View(context)
    private val row = LinearLayout(context)
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()
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
        labels.clear()
        count = tabs.size
        for (t in tabs) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val icon = ImageView(context).apply { setImageResource(t.iconRes) }
            item.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            val label = TextView(context).apply {
                text = t.label
                textSize = 12f
                gravity = Gravity.CENTER
            }
            item.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
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

    private fun itemWidth() = if (count == 0) 0f else width.toFloat() / count
    private fun bubbleW() = (itemWidth() - dp(12)).coerceAtLeast(0f)
    private fun slotX(i: Int) = i * itemWidth() + dp(6)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (count == 0) return
        val bh = h - dp(12)
        bubble.layoutParams = LayoutParams(bubbleW().toInt(), bh).apply { topMargin = dp(6) }
        (bubble.background as GradientDrawable).cornerRadius = bh / 2f
        bubble.translationX = slotX(selected)
    }

    fun select(i: Int, notify: Boolean = true) {
        if (count == 0) return
        val idx = i.coerceIn(0, count - 1)
        selected = idx
        if (width > 0) {
            bubble.animate().translationX(slotX(idx)).setDuration(180).start()
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
            labels[i].setTextColor(c)
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
