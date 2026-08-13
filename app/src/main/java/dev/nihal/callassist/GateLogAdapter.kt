package dev.nihal.callassist

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the gate event log the same way Recents renders calls: day headers,
 * grouped rounded cards, one row per gate run. The row states the outcome; the
 * individual steps stay collapsed until tapped.
 */
class GateLogAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val stepFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    // Keyed by adapter position rather than timestamp: legacy log lines can fail
    // to parse and collapse to time 0, which would make them share a key.
    private val expanded = mutableSetOf<Int>()

    var items: List<GateLog.Item> = emptyList()
        @Suppress("NotifyDataSetChanged")
        set(v) {
            field = v
            expanded.clear()
            notifyDataSetChanged()
        }

    override fun getItemCount() = items.size

    override fun getItemViewType(pos: Int) = if (items[pos] is GateLog.Item.Header) 0 else 1

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
    }

    class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.logRow)
        val icon: ImageView = v.findViewById(R.id.logIcon)
        val title: TextView = v.findViewById(R.id.logTitle)
        val subtitle: TextView = v.findViewById(R.id.logSubtitle)
        val time: TextView = v.findViewById(R.id.logTime)
        val steps: LinearLayout = v.findViewById(R.id.logSteps)
        val divider: View = v.findViewById(R.id.logDivider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_recent_header, parent, false))
        else EntryVH(inf.inflate(R.layout.item_gate_log, parent, false))
    }

    private fun iconFor(ctx: Context, outcome: GateLog.Outcome): Pair<Int, Int> = when (outcome) {
        GateLog.Outcome.OPENED ->
            R.drawable.ic_gate_ok to ContextCompat.getColor(ctx, R.color.green)
        GateLog.Outcome.WARNING ->
            R.drawable.ic_gate_warn to ContextCompat.getColor(ctx, R.color.red)
        GateLog.Outcome.RUNNING ->
            R.drawable.ic_phone to ContextCompat.getColor(ctx, R.color.textSecondary)
    }

    private fun titleFor(s: GateLog.Session): String = when (s.outcome) {
        GateLog.Outcome.OPENED -> "Gate opened"
        GateLog.Outcome.WARNING -> "Gate may not have opened"
        GateLog.Outcome.RUNNING -> "Gate call in progress"
    }

    private fun subtitleFor(s: GateLog.Session): String {
        val parts = mutableListOf<String>()
        s.label?.let { parts.add(it) }
        s.code?.let { parts.add("code $it") }
        val tries = s.steps.count { it.text.startsWith("Sending code") }
        if (tries > 1) parts.add("$tries tries")
        // Wall-clock length of the run, from answer to disconnect.
        val secs = (s.end - s.start) / 1000
        if (s.start > 0L && s.end > s.start && secs > 0) parts.add("${secs}s")
        if (parts.isEmpty()) parts.add("${s.steps.size} events")
        return parts.joinToString(" · ")
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val ctx = h.itemView.context
        when (val item = items[pos]) {
            is GateLog.Item.Header -> (h as HeaderVH).text.text = item.text
            is GateLog.Item.Entry -> {
                h as EntryVH
                val s = item.session
                val isOpen = expanded.contains(pos)
                val (iconRes, tint) = iconFor(ctx, s.outcome)

                (h.row.parent as View).setBackgroundResource(item.bg)
                h.icon.setImageResource(iconRes)
                h.icon.imageTintList = ColorStateList.valueOf(tint)
                h.title.text = titleFor(s)
                h.title.setTextColor(
                    if (s.outcome == GateLog.Outcome.WARNING) tint
                    else ContextCompat.getColor(ctx, R.color.textPrimary)
                )
                h.subtitle.text = subtitleFor(s)
                h.time.text = if (s.start > 0L) timeFmt.format(Date(s.start)) else ""
                h.divider.visibility = if (item.divider && !isOpen) View.VISIBLE else View.GONE

                h.steps.removeAllViews()
                h.steps.visibility = if (isOpen) View.VISIBLE else View.GONE
                if (isOpen) {
                    for (step in s.steps) {
                        h.steps.addView(stepView(ctx, step))
                    }
                }

                h.row.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (expanded.contains(p)) expanded.remove(p) else expanded.add(p)
                    notifyItemChanged(p)
                }
            }
        }
    }

    private fun stepView(ctx: Context, step: GateLog.Step): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        }
        val time = TextView(ctx).apply {
            text = if (step.time > 0L) stepFmt.format(Date(step.time)) else ""
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary))
        }
        // Colour only the steps that decide the outcome, so they stand out from
        // the routine ones without turning the list into confetti.
        val color = when {
            step.text.contains("code accepted") -> R.color.green
            step.text.contains("may not have opened") ||
                step.text.startsWith("Failsafe") ||
                step.text.startsWith("Could not mute") -> R.color.red
            else -> R.color.textSecondary
        }
        val text = TextView(ctx).apply {
            text = step.text
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, color))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(ctx, 12) }
        }
        row.addView(time)
        row.addView(text)
        return row
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
