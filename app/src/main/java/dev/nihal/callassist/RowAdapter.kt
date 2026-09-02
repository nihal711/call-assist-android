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
    private val palette = intArrayOf(
        0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(),
        0xFF42A5F5.toInt(), 0xFFFFA726.toInt(), 0xFF66BB6A.toInt(),
        0xFFEC407A.toInt(), 0xFF5C6BC0.toInt(), 0xFF8D6E63.toInt()
    )

    fun avatarColor(seed: String): Int =
        palette[Math.abs(seed.hashCode()) % palette.size]

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
     * Touch-down shrink with a springy release for pill buttons and call
     * tiles. Doesn't consume the event, so clicks and long-presses still fire.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressable(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                }
                false
            }
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
    private val photoCache = object : android.util.LruCache<String, android.graphics.Bitmap>(32) {}
    private val photoMisses = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Called after contacts are re-read, so edited photos aren't served stale. */
    fun clearPhotoCache() {
        photoCache.evictAll()
        photoMisses.clear()
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
    fun loadPhoto(ctx: android.content.Context, target: android.widget.ImageView, uri: String?) {
        if (uri.isNullOrBlank() || photoMisses.contains(uri)) {
            target.visibility = View.GONE
            return
        }
        val bmp = photoCache.get(uri) ?: decodeAvatar(ctx, uri)?.also { photoCache.put(uri, it) }
        if (bmp == null) {
            // Remember the failure so we don't retry the decode on every bind.
            photoMisses.add(uri)
            target.visibility = View.GONE
            return
        }
        target.setImageDrawable(
            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                .create(ctx.resources, bmp)
                .apply { isCircular = true }
        )
        target.visibility = View.VISIBLE
    }

    private fun decodeAvatar(ctx: android.content.Context, uri: String): android.graphics.Bitmap? {
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
            var half = minOf(bounds.outWidth, bounds.outHeight) / 2
            while (half >= AVATAR_PX) {
                sample *= 2
                half /= 2
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
        val photoUri: String? = null
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
        h.meta.setTextColor(r.metaColor ?: Color.GRAY)
        h.avatar.text = Ui.initial(r.avatarSeed)
        h.avatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(r.avatarSeed))
        // Cached + downsampled; hides itself (initial shows) when there is no photo.
        Ui.loadPhoto(h.itemView.context, h.photo, r.photoUri)
        h.itemView.setOnClickListener { onClick(r) }
    }
}
