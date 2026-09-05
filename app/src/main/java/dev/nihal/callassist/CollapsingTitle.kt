package dev.nihal.callassist

import android.view.View
import android.widget.TextView
import com.google.android.material.appbar.AppBarLayout

/**
 * Extended app bar on top of AppBarLayout: the header is a fixed
 * proportion of the display, the AppBarLayout collapses it before the list
 * scrolls, and the large centred title cross-fades into the toolbar's small
 * title over the collapse.
 */
object CollapsingTitle {
    fun attach(
        appBar: AppBarLayout,
        header: View,
        bigTitle: TextView,
        smallTitle: TextView,
        proportion: Float = 0.36f
    ) {
        val dm = appBar.resources.displayMetrics
        val density = dm.density
        header.layoutParams = header.layoutParams.apply {
            height = (dm.heightPixels * proportion).toInt()
                .coerceIn((140 * density).toInt(), (280 * density).toInt())
        }
        smallTitle.alpha = 0f
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { bar, offset ->
            val range = bar.totalScrollRange.takeIf { it > 0 } ?: return@OnOffsetChangedListener
            val f = (-offset / range.toFloat()).coerceIn(0f, 1f)
            bigTitle.alpha = (1f - f / 0.6f).coerceIn(0f, 1f)
            val s = 1f - 0.12f * f
            bigTitle.scaleX = s
            bigTitle.scaleY = s
            smallTitle.alpha = ((f - 0.6f) / 0.4f).coerceIn(0f, 1f)
        })
    }
}
