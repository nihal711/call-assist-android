package dev.nihal.callassist

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.RecyclerView

object Ui {
    // Every seed keeps white initials at >= 4.2:1 (WCAG large-text 3:1 with margin).
    private val palette = intArrayOf(
        0xFF6A4FB6.toInt(), 0xFF00897B.toInt(), 0xFFD84335.toInt(),
        0xFF1E6FBF.toInt(), 0xFFB36A00.toInt(), 0xFF2E8B57.toInt(),
        0xFFC2185B.toInt(), 0xFF4552A8.toInt(), 0xFF7A5548.toInt()
    )

    fun avatarColor(seed: String): Int =
        palette[Math.abs(seed.hashCode()) % palette.size]

    /**
     * Avatar disc: the seeded hue as a soft top-left-lit gradient rather than
     * a flat fill — a common treatment that keeps initials
     * from looking like coloured dots.
     */
    fun avatarBg(seed: String): android.graphics.drawable.Drawable {
        val base = avatarColor(seed)
        // Subtle lighting only: a stronger highlight pushed the top-left below contrast.
        val light = androidx.core.graphics.ColorUtils.blendARGB(base, Color.WHITE, 0.12f)
        val deep = androidx.core.graphics.ColorUtils.blendARGB(base, Color.BLACK, 0.08f)
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(light, base, deep)
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
    }

    fun fmt(number: String): String =
        android.telephony.PhoneNumberUtils.formatNumber(number, java.util.Locale.getDefault().country)
            ?: number

    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "#"
        val c = t.first()
        return if (c.isLetter()) c.uppercase() else "#"
    }

    /** [title] with a smaller, secondary-coloured [sub] line under it, for list rows. */
    fun twoLine(ctx: android.content.Context, title: CharSequence, sub: CharSequence): CharSequence {
        val sb = android.text.SpannableStringBuilder(title).append("\n")
        val start = sb.length
        sb.append(sub)
        val flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, sb.length, flags)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary)
            ),
            start, sb.length, flags
        )
        return sb
    }

    /**
     * Centre-cropped circular copy of [src] for the call notification —
     * which sits cleanly in the shade. A pre-rounded square fought the shade's
     * app-icon badge and its own icon handling and read as clipped.
     */
    fun circleCrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val shader = android.graphics.BitmapShader(
            src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        shader.setLocalMatrix(android.graphics.Matrix().apply {
            postTranslate((size - src.width) / 2f, (size - src.height) / 2f)
        })
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        val r = size / 2f
        canvas.drawRoundRect(android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }

    /**
     * Edge-to-edge (enforced from target SDK 35): transparent bars whose icon
     * colour follows the app's own light/dark mode rather than the system's,
     * since the app can be forced dark while the system is light.
     */
    fun edgeToEdge(a: androidx.activity.ComponentActivity) {
        val night = (a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val style = if (night) androidx.activity.SystemBarStyle.dark(Color.TRANSPARENT)
        else androidx.activity.SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        a.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /** Adds the system-bar (and optionally keyboard) insets to [v]'s existing padding. */
    fun applyInsets(v: View, top: Boolean = true, bottom: Boolean = true, ime: Boolean = false) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = if (ime) insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom else 0
            view.setPadding(
                l,
                t + if (top) bars.top else 0,
                r,
                b + maxOf(if (bottom) bars.bottom else 0, keyboard)
            )
            insets
        }
    }

    /**
     * Press feedback: a flat 0.96 shrink on touch-down, eased back on release —
     * no overshoot. Doesn't consume the event, so clicks and long-presses still fire.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressable(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                false
            }
        }
    }

    /**
     * Grows a small pill's hit area to the 48dp minimum without changing its
     * visual size: a TouchDelegate on the parent, refreshed after every layout.
     */
    fun expandTouch(v: View, extraDp: Int = 4) {
        val parent = v.parent as? View ?: return
        val extra = (extraDp * v.resources.displayMetrics.density).toInt()
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val r = android.graphics.Rect()
            v.getHitRect(r)
            r.inset(-extra, -extra)
            parent.touchDelegate = android.view.TouchDelegate(r, v)
        }
    }

    /** 150ms cross-fade between two sibling pages; [to] ends VISIBLE, [from] INVISIBLE. */
    fun crossfade(from: View?, to: View, duration: Long = 150) {
        // A fade-out still running on [to] (fast tab flicking) must not hide it at its end.
        to.animate().cancel()
        if (to.visibility != View.VISIBLE) {
            to.alpha = 0f
            to.visibility = View.VISIBLE
        }
        to.animate().alpha(1f).setDuration(duration).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        if (from != null && from !== to && from.visibility == View.VISIBLE) {
            from.animate().alpha(0f).setDuration(duration)
                .withEndAction { if (from.alpha == 0f) { from.visibility = View.INVISIBLE; from.alpha = 1f } }.start()
        }
    }

    /** Fades any visibility changes made to [root]'s children in this frame. */
    fun fadeChanges(root: android.view.ViewGroup, duration: Long = 180) {
        androidx.transition.TransitionManager.beginDelayedTransition(
            root, androidx.transition.Fade().setDuration(duration)
        )
    }

    /**
     * Keypad glyphs follow the font scale up to 1.3x, then stop: past that the
     * digits would no longer fit their fixed-height rows, and the keypad's
     * legibility comes from its size, not its type.
     */
    fun keyTextSize(tv: TextView, sp: Float) {
        val scale = tv.resources.configuration.fontScale.coerceAtMost(1.3f)
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sp * scale)
    }

    /** Portrait-only on phones; the unfolded/tablet layouts rotate freely. */
    fun lockPhonePortrait(a: android.app.Activity) {
        if (a.resources.configuration.smallestScreenWidthDp < 600) {
            a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    /** Empty-state text with a large faded glyph above it. */
    fun emptyState(tv: TextView, iconRes: Int) {
        val ctx = tv.context
        val size = (56 * ctx.resources.displayMetrics.density).toInt()
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes)?.mutate() ?: return
        d.setBounds(0, 0, size, size)
        d.setTint(androidx.core.content.ContextCompat.getColor(ctx, R.color.textSecondary))
        d.alpha = 110
        tv.setCompoundDrawables(null, d, null, null)
        tv.compoundDrawablePadding = (14 * ctx.resources.displayMetrics.density).toInt()
    }

    /** Avatars are small and repeat across rows, so decoded results are reused. */
    private const val AVATAR_PX = 160
    private val photoCache = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val photoMisses = java.util.Collections.synchronizedSet(HashSet<String>())
    private val photoExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val photoLock = Any()

    private data class PhotoWaiter(
        val target: java.lang.ref.WeakReference<android.widget.ImageView>,
        val key: String,
        val circular: Boolean,
        val callback: ((Boolean) -> Unit)?
    )

    private val photoWaiters = HashMap<String, MutableList<PhotoWaiter>>()

    /** Called after contacts are re-read, so edited photos aren't served stale. */
    fun clearPhotoCache() {
        synchronized(photoLock) {
            photoCache.evictAll()
            photoMisses.clear()
        }
    }

    /**
     * Shows the contact's photo in [target], circle-cropped, hiding it (so the
     * coloured initial behind shows instead) when there is no photo or it can't
     * be read.
     *
     * Phone.PHOTO_URI is the full-size image, not the thumbnail, so it is
     * downsampled to roughly avatar size before decoding and cached — this runs
     * during list binds, where decoding a full photo per row would stutter.
     */
    fun loadPhoto(
        ctx: android.content.Context,
        target: android.widget.ImageView,
        uri: String?,
        circular: Boolean = true,
        targetPx: Int = AVATAR_PX,
        onLoaded: ((Boolean) -> Unit)? = null
    ) {
        val key = uri?.takeIf { it.isNotBlank() }?.let { "$it#$targetPx" }
        target.tag = key
        if (key == null) {
            target.visibility = View.GONE
            target.setImageDrawable(null)
            onLoaded?.invoke(false)
            return
        }
        val cached = synchronized(photoLock) { photoCache.get(key) }
        if (cached != null) {
            applyPhoto(target, cached, circular)
            onLoaded?.invoke(true)
            return
        }
        if (photoMisses.contains(key)) {
            target.visibility = View.GONE
            onLoaded?.invoke(false)
            return
        }
        // Hide any bitmap left by a recycled holder while its new image loads.
        target.visibility = View.GONE
        val waiter = PhotoWaiter(java.lang.ref.WeakReference(target), key, circular, onLoaded)
        val shouldDecode = synchronized(photoLock) {
            val list = photoWaiters[key]
            if (list != null) {
                list.add(waiter)
                false
            } else {
                photoWaiters[key] = mutableListOf(waiter)
                true
            }
        }
        if (!shouldDecode) return
        val app = ctx.applicationContext
        photoExecutor.execute {
            val bmp = decodePhotoBlocking(app, uri, targetPx)
            val waiters = synchronized(photoLock) {
                if (bmp != null) photoCache.put(key, bmp) else photoMisses.add(key)
                photoWaiters.remove(key).orEmpty()
            }
            for (request in waiters) {
                val image = request.target.get() ?: continue
                image.post {
                    if (image.tag != request.key) return@post
                    if (bmp != null) applyPhoto(image, bmp, request.circular)
                    else image.visibility = View.GONE
                    request.callback?.invoke(bmp != null)
                }
            }
        }
    }

    private fun applyPhoto(
        target: android.widget.ImageView,
        bmp: android.graphics.Bitmap,
        circular: Boolean
    ) {
        if (circular) {
            target.setImageDrawable(
            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                    .create(target.resources, bmp)
                .apply { isCircular = true }
            )
        } else {
            target.setImageBitmap(bmp)
        }
        target.visibility = View.VISIBLE
    }

    /** Blocking, sampled decode used by background-only notification work too. */
    fun decodePhotoBlocking(
        ctx: android.content.Context,
        uri: String,
        targetPx: Int = AVATAR_PX
    ): android.graphics.Bitmap? {
        val parsed = android.net.Uri.parse(uri)
        return try {
            // Pass 1: read the dimensions only, so we can pick a sample size.
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ctx.contentResolver.openInputStream(parsed)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            var largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / 2 >= targetPx) {
                sample *= 2
                largest /= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(parsed)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** One list adapter reused by the T9 suggestions, recents, and contacts pages. */
class RowAdapter(private val onClick: (Row) -> Unit) :
    RecyclerView.Adapter<RowAdapter.VH>() {

    data class Row(
        val title: CharSequence,
        val subtitle: CharSequence,
        val meta: CharSequence,
        val avatarSeed: String,
        val payload: Any?,
        val metaColor: Int? = null,
        val photoUri: String? = null,
        /** Glyph in place of the initial (e.g. blocked numbers) */
        val iconRes: Int? = null
    )

    var rows: List<Row> = emptyList()
        @Suppress("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val photo: android.widget.ImageView = v.findViewById(R.id.avatarPhoto)
        val icon: android.widget.ImageView = v.findViewById(R.id.avatarIcon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = rows[pos]
        h.title.text = r.title
        h.subtitle.text = r.subtitle
        h.subtitle.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.meta.text = r.meta
        h.meta.setTextColor(
            r.metaColor ?: androidx.core.content.ContextCompat.getColor(h.itemView.context, R.color.textSecondary)
        )
        if (r.iconRes != null) {
            h.icon.setImageResource(r.iconRes)
            h.icon.visibility = View.VISIBLE
            h.avatar.visibility = View.INVISIBLE
            h.photo.visibility = View.GONE
        } else {
            h.icon.visibility = View.GONE
            h.avatar.visibility = View.VISIBLE
            h.avatar.text = Ui.initial(r.avatarSeed)
            h.avatar.background = Ui.avatarBg(r.avatarSeed)
            // Cached + downsampled; hides itself (initial shows) when there is no photo.
            Ui.loadPhoto(h.itemView.context, h.photo, r.photoUri)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }
}
