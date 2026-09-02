package dev.nihal.callassist

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Grouped contacts: section headers (Favourites, A–Z) over grouped
 * rounded cards. Tapping a row expands it inline into a detail card — big
 * name, one tappable line per number, and a row of round call / message /
 * edit / block actions. Tapping the expanded header collapses it again.
 */
class ContactsAdapter(
    private val onCall: (ContactsRepo.Contact, ContactsRepo.PhoneEntry?) -> Unit,
    private val onMessage: (ContactsRepo.Contact) -> Unit,
    private val onEdit: (ContactsRepo.Contact) -> Unit,
    private val onBlock: (ContactsRepo.Contact) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Header(val text: String, val count: String = "") : Item()
        data class Entry(
            val contact: ContactsRepo.Contact,
            val title: CharSequence,
            val subtitle: CharSequence,
            val bg: Int,
            val divider: Boolean
        ) : Item()
    }

    private var expandedPos = -1

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        // A toggled row is rebound in place (same holder) and animates its own
        // height — see ExpandAnim — instead of the default cross-fade.
        (rv.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        ExpandAnim.cancel(h.itemView)
    }

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
        val count: TextView = v.findViewById(R.id.headerCount)
    }

    class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.contactRow)
        val avatar: TextView = v.findViewById(R.id.avatar)
        val photo: ImageView = v.findViewById(R.id.photo)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val star: View = v.findViewById(R.id.star)
        val divider: View = v.findViewById(R.id.contactDivider)
        val expanded: View = v.findViewById(R.id.contactExpanded)
        val expandedHeader: View = v.findViewById(R.id.contactExpandedHeader)
        val expName: TextView = v.findViewById(R.id.expName)
        val expSub: TextView = v.findViewById(R.id.expSub)
        val expAvatar: TextView = v.findViewById(R.id.expAvatar)
        val expPhoto: ImageView = v.findViewById(R.id.expPhoto)
        val expNumbers: LinearLayout = v.findViewById(R.id.expNumbers)
        val btnCall: ImageButton = v.findViewById(R.id.btnCall)
        val btnMessage: ImageButton = v.findViewById(R.id.btnMessage)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEdit)
        val btnBlock: ImageButton = v.findViewById(R.id.btnBlock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_recent_header, parent, false))
        else EntryVH(inf.inflate(R.layout.item_contact, parent, false))
    }

    override fun getItemCount() = items.size

    /** Two-pane mode (wide screens): taps select instead of expanding inline; see [bindDetail]. */
    var onSelect: ((Item.Entry) -> Unit)? = null
    private var selectedId = -1L

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int, payloads: MutableList<Any>) {
        if (ExpandAnim.TOGGLE in payloads && h is EntryVH && h.itemView.height > 0) {
            val startH = h.itemView.height
            onBindViewHolder(h, pos)
            val expanded = pos == expandedPos && onSelect == null
            ExpandAnim.run(h.itemView, startH, if (expanded) h.expanded else h.row)
        } else {
            onBindViewHolder(h, pos)
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val ctx = h.itemView.context
        when (val item = items[pos]) {
            is Item.Header -> {
                h as HeaderVH
                h.text.text = item.text
                h.count.text = item.count
                h.count.visibility = if (item.count.isEmpty()) View.GONE else View.VISIBLE
            }
            is Item.Entry -> {
                h as EntryVH
                val c = item.contact
                val isExpanded = pos == expandedPos && onSelect == null
                h.itemView.setBackgroundResource(item.bg)
                h.row.visibility = if (isExpanded) View.GONE else View.VISIBLE
                h.expanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                h.divider.visibility = if (item.divider && !isExpanded) View.VISIBLE else View.GONE

                if (!isExpanded) {
                    h.avatar.text = Ui.initial(c.name)
                    h.avatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(c.name))
                    Ui.loadPhoto(ctx, h.photo, c.photoUri)
                    h.title.text = item.title
                    h.subtitle.text = item.subtitle
                    h.star.visibility = if (c.starred) View.VISIBLE else View.GONE
                    if (onSelect != null && c.id == selectedId) {
                        h.row.setBackgroundResource(R.drawable.bg_row_selected)
                    } else {
                        h.row.setBackgroundResource(RecentsItems.rowRipple(item.bg))
                    }
                    h.row.setOnClickListener {
                        if (onSelect != null) {
                            val old = selectedId
                            selectedId = c.id
                            items.indexOfFirst { it is Item.Entry && it.contact.id == old }
                                .takeIf { it >= 0 }?.let { notifyItemChanged(it) }
                            notifyItemChanged(h.bindingAdapterPosition)
                            onSelect?.invoke(item)
                        } else {
                            val old = expandedPos
                            expandedPos = h.bindingAdapterPosition
                            if (old >= 0) notifyItemChanged(old, ExpandAnim.TOGGLE)
                            notifyItemChanged(expandedPos, ExpandAnim.TOGGLE)
                        }
                    }
                } else {
                    bindExpanded(h, c, detail = false)
                }
            }
        }
    }

    /** Binds a standalone item_contact as the detail card of the two-pane layout. */
    fun bindDetail(view: View, entry: Item.Entry) {
        val h = EntryVH(view)
        view.setBackgroundResource(R.drawable.bg_group_single)
        h.row.visibility = View.GONE
        h.expanded.visibility = View.VISIBLE
        h.divider.visibility = View.GONE
        bindExpanded(h, entry.contact, detail = true)
    }

    private fun bindExpanded(h: EntryVH, c: ContactsRepo.Contact, detail: Boolean) {
        val ctx = h.itemView.context
        h.expName.text = c.name
        h.expSub.text = Ui.fmt(c.numbers.first().number)
        h.expAvatar.text = Ui.initial(c.name)
        h.expAvatar.backgroundTintList = ColorStateList.valueOf(Ui.avatarColor(c.name))
        Ui.loadPhoto(ctx, h.expPhoto, c.photoUri)
        bindNumbers(h.expNumbers, c)
        if (detail) {
            h.expandedHeader.isClickable = false
        } else {
            h.expandedHeader.setOnClickListener {
                val p = h.bindingAdapterPosition
                expandedPos = -1
                notifyItemChanged(p, ExpandAnim.TOGGLE)
            }
        }
        h.btnCall.setOnClickListener { onCall(c, null) }
        h.btnMessage.setOnClickListener { onMessage(c) }
        h.btnEdit.setOnClickListener { onEdit(c) }
        h.btnBlock.setOnClickListener { onBlock(c) }
    }

    /** "Mobile  +65 9123 4567" per number, each a tap-to-call line. */
    private fun bindNumbers(box: LinearLayout, c: ContactsRepo.Contact) {
        val ctx = box.context
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val density = ctx.resources.displayMetrics.density
        val padV = (7 * density).toInt()
        val padH = (4 * density).toInt()
        box.removeAllViews()
        for (n in c.numbers) {
            val label = n.label.ifBlank { "Phone" }
            val text = SpannableStringBuilder().apply {
                append(label)
                setSpan(ForegroundColorSpan(secondary), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.9f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("   ")
                val start = length
                append(Ui.fmt(n.number))
                setSpan(ForegroundColorSpan(primary), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            box.addView(TextView(ctx).apply {
                this.text = text
                textSize = 15f
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { onCall(c, n) }
            })
        }
    }
}
