package dev.nihal.callassist

import android.content.res.ColorStateList
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Recents: day headers + grouped rounded cards. */
class RecentsAdapter(private val onClick: (String) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val text: String) : Item()
        data class Entry(
            val title: String,
            val count: Int,
            val time: String,
            val type: Int,
            val number: String,
            val bg: Int,          // one of the bg_group_* drawables
            val divider: Boolean
        ) : Item()
    }

    var items: List<Item> = emptyList()
        @Suppress("NotifyDataSetChanged")
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getItemViewType(pos: Int) = if (items[pos] is Item.Header) 0 else 1

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
    }

    class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.recentRow)
        val icon: ImageView = v.findViewById(R.id.recentIcon)
        val title: TextView = v.findViewById(R.id.recentTitle)
        val time: TextView = v.findViewById(R.id.recentTime)
        val divider: View = v.findViewById(R.id.recentDivider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_recent_header, parent, false))
        else EntryVH(inf.inflate(R.layout.item_recent, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val ctx = h.itemView.context
        when (val item = items[pos]) {
            is Item.Header -> (h as HeaderVH).text.text = item.text
            is Item.Entry -> {
                h as EntryVH
                val green = ContextCompat.getColor(ctx, R.color.green)
                val red = ContextCompat.getColor(ctx, R.color.red)
                val accent = ContextCompat.getColor(ctx, R.color.accent)
                val gray = ContextCompat.getColor(ctx, R.color.textSecondary)
                val (iconRes, tint) = when (item.type) {
                    CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_out to green
                    CallLog.Calls.MISSED_TYPE -> R.drawable.ic_call_missed to red
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE ->
                        R.drawable.ic_block to accent
                    else -> R.drawable.ic_call_in to gray
                }
                h.icon.setImageResource(iconRes)
                h.icon.imageTintList = ColorStateList.valueOf(tint)
                h.title.text = if (item.count > 1) "${item.title} (${item.count})" else item.title
                h.time.text = item.time
                h.time.setTextColor(if (item.type == CallLog.Calls.MISSED_TYPE) red else gray)
                h.row.setBackgroundResource(item.bg)
                h.divider.visibility = if (item.divider) View.VISIBLE else View.GONE
                h.row.setOnClickListener { onClick(item.number) }
            }
        }
    }
}
