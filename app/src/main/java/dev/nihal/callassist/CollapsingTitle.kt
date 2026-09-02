package dev.nihal.callassist

import android.util.TypedValue
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Large-title behaviour: the page title shrinks and its top
 * breathing room closes as the list scrolls, settling into a compact
 * toolbar; scrolling back to the top restores it.
 */
object CollapsingTitle {
    fun attach(
        rv: RecyclerView,
        title: TextView,
        expandedSp: Float,
        collapsedSp: Float,
        expandedTopDp: Int,
        collapsedTopDp: Int
    ) {
        val density = rv.resources.displayMetrics.density
        val range = 96 * density
        var last = -1f
        fun apply(f: Float) {
            if (kotlin.math.abs(f - last) < 0.01f) return
            last = f
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, expandedSp + (collapsedSp - expandedSp) * f)
            val top = ((expandedTopDp + (collapsedTopDp - expandedTopDp) * f) * density).toInt()
            title.setPadding(title.paddingLeft, top, title.paddingRight, title.paddingBottom)
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                apply((r.computeVerticalScrollOffset() / range).coerceIn(0f, 1f))
            }
        })
    }
}
