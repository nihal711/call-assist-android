package dev.nihal.callassist

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Turns call-log rows into the day-grouped, merged items the Recents list shows. */
object RecentsItems {

    fun dayLabel(ts: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today"
            sameYear && dayDiff == 1 -> "Yesterday"
            else -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ts))
        }
    }

    /** Rounded-corner background for row [index] of a card holding [size] rows. */
    fun groupBg(index: Int, size: Int): Int = when {
        size == 1 -> R.drawable.bg_group_single
        index == 0 -> R.drawable.bg_group_top
        index == size - 1 -> R.drawable.bg_group_bottom
        else -> R.drawable.bg_group_mid
    }

    /** Press ripple whose mask matches the card piece a row sits in. */
    fun rowRipple(bg: Int): Int = when (bg) {
        R.drawable.bg_group_single -> R.drawable.bg_row_ripple_single
        R.drawable.bg_group_top -> R.drawable.bg_row_ripple_top
        R.drawable.bg_group_bottom -> R.drawable.bg_row_ripple_bottom
        else -> R.drawable.bg_row_ripple_mid
    }

    fun build(ctx: Context, log: List<ContactsRepo.CallEntry>): List<RecentsAdapter.Item> {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        // Merge consecutive entries with the same number, type, SIM, and day.
        class Group(val e: ContactsRepo.CallEntry, var count: Int, val ids: MutableList<Long>)

        val merged = ArrayList<Group>()
        val dual = SimUtil.isDual(ctx)
        for (e in log) {
            val last = merged.lastOrNull()
            if (last != null && last.e.type == e.type &&
                last.e.number.filter { it.isDigit() } == e.number.filter { it.isDigit() } &&
                last.e.accountId == e.accountId &&
                dayLabel(last.e.date) == dayLabel(e.date)
            ) {
                last.count++
                last.ids.add(e.id)
            } else {
                merged.add(Group(e, 1, mutableListOf(e.id)))
            }
        }

        val items = ArrayList<RecentsAdapter.Item>()
        var i = 0
        while (i < merged.size) {
            val label = dayLabel(merged[i].e.date)
            var j = i
            while (j < merged.size && dayLabel(merged[j].e.date) == label) j++
            items.add(RecentsAdapter.Item.Header(label))
            for (k in i until j) {
                val g = merged[k]
                val name = g.e.name?.takeIf { it.isNotBlank() }
                    ?: ContactsRepo.lookupNameCached(g.e.number)
                    ?: Ui.fmt(g.e.number)
                items.add(
                    RecentsAdapter.Item.Entry(
                        title = name,
                        count = g.count,
                        time = timeFmt.format(Date(g.e.date)),
                        type = g.e.type,
                        number = g.e.number,
                        duration = g.e.duration,
                        bg = groupBg(k - i, j - i),
                        divider = k < j - 1,
                        ids = g.ids,
                        sim = if (dual) SimUtil.byLogColumns(ctx, g.e.accountComponent, g.e.accountId) else null
                    )
                )
            }
            i = j
        }
        return items
    }
}
