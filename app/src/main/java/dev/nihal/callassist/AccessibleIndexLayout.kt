package dev.nihal.callassist

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Touch-driven contacts index that also exposes a proper accessibility click. */
class AccessibleIndexLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
