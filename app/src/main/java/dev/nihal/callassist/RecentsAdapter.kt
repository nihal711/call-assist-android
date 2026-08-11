package dev.nihal.callassist

import android.content.res.ColorStateList
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Recents: day headers + grouped rounded cards. Tapping an entry
 * expands it inline into a detail card with call/message/contact/history
 * actions (when [expandable]); tapping the expanded header collapses it.
 */
class RecentsAdapter(
    private val expandable: Boolean = true,
    private val onCall: (String) -> Unit,
    private val onMessage: (String) -> Unit = {},
    private val onContact: (String) -> Unit = {},
    private val onHistory: (Item.Entry) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val text: String) : Item()
        data class Entry(
            val title: String,
            val count: Int,
            val time: String,
            val type: Int,
            val number: String,
            val duration: Long,
            val bg: Int,
            val divider: Boolean
        ) : Item()
    }

    private var expandedPos = -1

    var items: List<Item> = emptyList()
        @Suppress("NotifyDataSetChanged")
        set(v) {
            field = v
            expandedPos = -1
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
        val expanded: View = v.findViewById(R.id.expandedBlock)
        val expandedHeader: View = v.findViewById(R.id.expandedHeader)
        val expName: TextView = v.findViewById(R.id.expName)
        val expNumber: TextView = v.findViewById(R.id.expNumber)
        val expAvatar: TextView = v.findViewById(R.id.expAvatar)
        val expStatusIcon: ImageView = v.findViewById(R.id.expStatusIcon)
        val expStatus: TextView = v.findViewById(R.id.expStatus)
        val expTime: TextView = v.findViewById(R.id.expTime)
        val btnCall: ImageButton = v.findViewById(R.id.btnExpCall)
        val btnMsg: ImageButton = v.findViewById(R.id.btnExpMsg)
        val btnContact: ImageButton = v.findViewById(R.id.btnExpContact)
        val btnHistory: ImageButton = v.findViewById(R.id.btnExpHistory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_recent_header, parent, false))
        else EntryVH(inf.inflate(R.layout.item_recent, parent, false))
    }

    override fun getItemCount() = items.size

    private fun iconFor(ctx: android.content.Context, type: Int): Pair<Int, Int> {
        val green = ContextCompat.getColor(ctx, R.color.green)
        val red = ContextCompat.getColor(ctx, R.color.red)
        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val gray = ContextCompat.getColor(ctx, R.color.textSecondary)
        return when (type) {
            CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_out to green
            CallLog.Calls.MISSED_TYPE -> R.drawable.ic_call_missed to red
            CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_block to accent
            else -> R.drawable.ic_call_in to gray
        }
    }

    private fun statusText(e: Item.Entry): String {
        val base = when (e.type) {
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
            CallLog.Calls.MISSED_TYPE -> "Missed call"
            CallLog.Calls.REJECTED_TYPE -> "Rejected call"
            CallLog.Calls.BLOCKED_TYPE -> "Blocked call"
            else -> "Incoming call"
        }
        val withDuration = e.type == CallLog.Calls.OUTGOING_TYPE || e.type == CallLog.Calls.INCOMING_TYPE
        return if (withDuration) "$base, ${e.duration / 60} mins ${e.duration % 60} secs" else base
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val ctx = h.itemView.context
        when (val item = items[pos]) {
            is Item.Header -> (h as HeaderVH).text.text = item.text
            is Item.Entry -> {
                h as EntryVH
                val red = ContextCompat.getColor(ctx, R.color.red)
                val gray = ContextCompat.getColor(ctx, R.color.textSecondary)
                val isExpanded = pos == expandedPos
                val (iconRes, tint) = iconFor(ctx, item.type)

                h.itemView.findViewById<View>(R.id.recentRow).visibility =
                    if (isExpanded) View.GONE else View.VISIBLE
                h.expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                (h.row.parent as View).setBackgroundResource(item.bg)
                h.divider.visibility = if (item.divider && !isExpanded) View.VISIBLE else View.GONE

                if (!isExpanded) {
                    h.icon.setImageResource(iconRes)
                    h.icon.imageTintList = ColorStateList.valueOf(tint)
                    h.title.text = if (item.count > 1) "${item.title} (${item.count})" else item.title
                    h.time.text = item.time
                    h.time.setTextColor(if (item.type == CallLog.Calls.MISSED_TYPE) red else gray)
                    h.row.setOnClickListener {
                        if (!expandable) {
                            onCall(item.number)
                            return@setOnClickListener
                        }
                        val old = expandedPos
                        expandedPos = h.bindingAdapterPosition
                        if (old >= 0) notifyItemChanged(old)
                        notifyItemChanged(expandedPos)
                    }
                } else {
                    h.expName.text = if (item.count > 1) "${item.title} (${item.count})" else item.title
                    h.expNumber.text =
                        PhoneNumberUtils.formatNumber(item.number, Locale.getDefault().country)
                            ?: item.number
                    h.expAvatar.text = Ui.initial(item.title)
                    h.expAvatar.backgroundTintList =
                        ColorStateList.valueOf(Ui.avatarColor(item.title))
                    h.expStatusIcon.setImageResource(iconRes)
                    h.expStatusIcon.imageTintList = ColorStateList.valueOf(tint)
                    h.expStatus.text = statusText(item)
                    h.expStatus.setTextColor(tint)
                    h.expTime.text = item.time
                    h.expandedHeader.setOnClickListener {
                        val p = h.bindingAdapterPosition
                        expandedPos = -1
                        notifyItemChanged(p)
                    }
                    h.btnCall.setOnClickListener { onCall(item.number) }
                    h.btnMsg.setOnClickListener { onMessage(item.number) }
                    h.btnContact.setOnClickListener { onContact(item.number) }
                    h.btnHistory.setOnClickListener { onHistory(item) }
                }
            }
        }
    }
}
