package dev.nihal.callassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Swipe a Recents row right to call, left to message. */
class RecentsSwipeCallback(
    private val ctx: Context,
    private val adapter: RecentsAdapter,
    private val onCall: (String) -> Unit,
    private val onMessage: (String) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val cap = dp(110).toFloat()
    private val helper = ItemTouchHelper(this)
    private var list: RecyclerView? = null

    // Mutated once: tint/alpha set per frame must not leak into the shared
    // drawable state that every other ic_phone / ic_message instance reads.
    private val callIcon: Drawable? = ContextCompat.getDrawable(ctx, R.drawable.ic_phone)?.mutate()?.apply {
        setTint(ContextCompat.getColor(ctx, R.color.green))
    }
    private val messageIcon: Drawable? = ContextCompat.getDrawable(ctx, R.drawable.ic_message)?.mutate()?.apply {
        setTint(ContextCompat.getColor(ctx, R.color.accent))
    }

    fun attach(rv: RecyclerView) {
        list = rv
        helper.attachToRecyclerView(rv)
    }

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

    override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
        val pos = vh.bindingAdapterPosition
        return if (pos >= 0 && adapter.isSwipeable(pos)) super.getSwipeDirs(rv, vh) else 0
    }

    // Below the travel cap, or a full swipe could never trigger.
    override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.25f

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        val e = adapter.items.getOrNull(pos) as? RecentsAdapter.Item.Entry ?: return
        // The row is rebound in place (change animations are off), so the
        // helper's finished recover animation would otherwise stay registered
        // and re-apply its full-width offset on every later draw. Detaching
        // and re-attaching the helper ends every recover animation and runs
        // clearView, which is the only supported way to reset that state.
        vh.itemView.post {
            list?.let { rv ->
                helper.attachToRecyclerView(null)
                helper.attachToRecyclerView(rv)
            }
            vh.itemView.translationX = 0f
            adapter.notifyItemChanged(pos)
        }
        if (direction == ItemTouchHelper.RIGHT) onCall(e.number) else onMessage(e.number)
    }

    override fun onChildDraw(
        c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        val x = dX.coerceIn(-cap, cap)
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) drawHint(c, vh.itemView, x)
        super.onChildDraw(c, rv, vh, x, dY, actionState, isCurrentlyActive)
    }

    /** Phone (right) or message (left) glyph fading in behind the swiped row. */
    private fun drawHint(c: Canvas, item: View, dX: Float) {
        if (dX == 0f) return
        val icon = (if (dX > 0) callIcon else messageIcon) ?: return
        icon.alpha = (kotlin.math.abs(dX) / cap * 255).toInt().coerceAtMost(255)
        val size = dp(24)
        val cy = (item.top + item.bottom) / 2
        if (dX > 0) {
            icon.setBounds(item.left + dp(30), cy - size / 2, item.left + dp(30) + size, cy + size / 2)
        } else {
            icon.setBounds(item.right - dp(30) - size, cy - size / 2, item.right - dp(30), cy + size / 2)
        }
        icon.draw(c)
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
