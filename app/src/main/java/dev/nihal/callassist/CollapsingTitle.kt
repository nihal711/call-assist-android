package dev.nihal.callassist

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Extended app bar: a tall header (a fixed proportion of the screen)
 * with the title centred in it, collapsing on scroll into a 56dp toolbar whose
 * own small title fades in as the large one fades out.
 */
object CollapsingTitle {
    /** Header height as a proportion of the display height. */
    private const val PROPORTION = 0.36f

    fun attach(
        rv: RecyclerView,
        header: View,
        bigTitle: TextView,
        smallTitle: TextView,
        collapsedDp: Int = 56
    ) {
        val dm = rv.resources.displayMetrics
        val density = dm.density
        val collapsed = (collapsedDp * density).toInt()
        val expanded = (dm.heightPixels * PROPORTION).toInt().coerceIn((160 * density).toInt(), (280 * density).toInt())
        val range = (expanded - collapsed).toFloat()
        // The list must always be able to scroll by the collapse range, or a
        // short list would collapse the header, grow, and snap back open.
        rv.setPaddingRelative(rv.paddingStart, rv.paddingTop, rv.paddingEnd, rv.paddingBottom + range.toInt())
        var last = -1f
        fun apply(raw: Float) {
            val f = (raw * 24).toInt() / 24f
            if (f == last) return
            last = f
            header.layoutParams = header.layoutParams.apply { height = (expanded - range * f).toInt() }
            bigTitle.alpha = (1f - f / 0.6f).coerceIn(0f, 1f)
            val s = 1f - 0.12f * f
            bigTitle.scaleX = s
            bigTitle.scaleY = s
            smallTitle.alpha = ((f - 0.6f) / 0.4f).coerceIn(0f, 1f)
        }
        apply(0f)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                apply((r.computeVerticalScrollOffset() / range).coerceIn(0f, 1f))
            }
        })
    }
}
