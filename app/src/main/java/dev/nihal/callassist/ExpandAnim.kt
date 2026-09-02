package dev.nihal.callassist

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * Expands or collapses a list card in place by animating its own height
 * (RecyclerView re-lays out the neighbours every frame, so they glide) while
 * the newly shown content fades in.
 *
 * Replaces a TransitionManager/ChangeBounds pass over the whole list, which
 * snapshotted every row and then — because RecyclerView recycles views — slid
 * a rebound view from where a *different* contact had been, with a flicker
 * at the rebind.
 */
object ExpandAnim {
    private val ease = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)

    /** [root] is already bound to its final state; [startH] is its height before that bind. */
    fun run(root: View, startH: Int, revealed: View) {
        (root.tag as? ValueAnimator)?.cancel()
        val lp = root.layoutParams ?: return
        if (root.width == 0) return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val endH = root.measuredHeight
        if (endH == startH) {
            root.layoutParams = lp
            return
        }
        lp.height = startH
        root.layoutParams = lp
        revealed.alpha = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = ease
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.height = (startH + (endH - startH) * t).toInt()
                root.layoutParams = lp
                revealed.alpha = t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = finish()
                override fun onAnimationCancel(a: Animator) = finish()
                private fun finish() {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    root.layoutParams = lp
                    revealed.alpha = 1f
                    root.tag = null
                }
            })
        }
        root.tag = anim
        anim.start()
    }

    /** Recycled mid-animation: snap to the resting state. */
    fun cancel(root: View) {
        (root.tag as? ValueAnimator)?.cancel()
    }

    const val TOGGLE = "toggle"
}
