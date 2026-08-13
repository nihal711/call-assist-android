package dev.nihal.callassist

import android.app.Dialog
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Real cross-window background blur for dialogs (Android 12+).
 *
 * [android.view.Window.setBackgroundBlurRadius] blurs the actual app content
 * behind the dialog window — the compositor does it on the GPU, so it is much
 * cheaper than anything we could draw ourselves, and it is what the system's own
 * panels use.
 *
 * It is not always available: the platform gates it behind a system-wide flag
 * that is switched off by battery saver, by "reduce transparency"-style
 * accessibility settings, and on devices whose compositor opts out. The flag
 * can also flip while the dialog is on screen, so we listen for changes rather
 * than only sampling once. When blur is off we fall back to the opaque-ish
 * @color/navBg fill, which is why the panel background stays a solid colour.
 */
object Glass {

    /** Matches the dialog's 32dp corner radius closely enough to read as one surface. */
    private const val BLUR_RADIUS_DP = 48

    /**
     * @param panel the view whose background is the glass surface — it is
     *   swapped to a more transparent fill while blur is active, otherwise the
     *   opaque fill would hide the blur entirely.
     */
    fun applyDialogBlur(dialog: Dialog, panel: android.view.View? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val window = dialog.window ?: return
        val ctx = dialog.context
        val radius = (BLUR_RADIUS_DP * ctx.resources.displayMetrics.density).toInt()

        // Lets the blur show through instead of the dim layer swallowing it.
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val decor = window.decorView

        fun setEnabled(enabled: Boolean) {
            window.setBackgroundBlurRadius(if (enabled) radius else 0)
            window.attributes = window.attributes.apply {
                dimAmount = if (enabled) 0.25f else 0.55f
            }
            panel?.setBackgroundResource(
                if (enabled) R.drawable.bg_dialog_blur else R.drawable.bg_dialog
            )
        }

        val listener = java.util.function.Consumer<Boolean> { enabled ->
            decor.post { setEnabled(enabled) }
        }

        decor.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                val wm = ContextCompat.getSystemService(ctx, WindowManager::class.java) ?: return
                setEnabled(wm.isCrossWindowBlurEnabled)
                wm.addCrossWindowBlurEnabledListener(listener)
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                ContextCompat.getSystemService(ctx, WindowManager::class.java)
                    ?.removeCrossWindowBlurEnabledListener(listener)
            }
        })
    }
}
