package dev.nihal.callassist

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

object Ui {
    private val palette = intArrayOf(
        0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(),
        0xFF42A5F5.toInt(), 0xFFFFA726.toInt(), 0xFF66BB6A.toInt(),
        0xFFEC407A.toInt(), 0xFF5C6BC0.toInt(), 0xFF8D6E63.toInt()
    )

    fun avatarColor(seed: String): Int =
        palette[Math.abs(seed.hashCode()) % palette.size]

    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "#"
        val c = t.first()
        return if (c.isLetter()) c.uppercase() else "#"
    }
}

/** One list adapter reused by the T9 suggestions, recents, and contacts pages. */
class RowAdapter(private val onClick: (Row) -> Unit) :
    RecyclerView.Adapter<RowAdapter.VH>() {

    data class Row(
        val title: String,
        val subtitle: String,
        val meta: String,
        val avatarSeed: String,
        val payload: Any?,
        val metaColor: Int? = null
    )

    var rows: List<Row> = emptyList()
        @Suppress("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
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
        h.itemView.setOnClickListener { onClick(r) }
    }
}
