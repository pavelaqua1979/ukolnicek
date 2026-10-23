package cz.paul.ukolnicek

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Měsíční kalendář na hlavní obrazovce.
 * Dny s úkoly mají tečky (●), u opakovaných úkolů i budoucí termíny.
 * Víkendy šedě, státní svátky červeně, dnešek orámovaný.
 * Klepnutí na den -> onDaySelected(den), opětovné klepnutí -> onDaySelected(null) = zrušit výběr.
 */
class MonthCalendar(
    private val act: Activity,
    private val onDaySelected: (Calendar?) -> Unit
) {
    private val cs = Locale("cs")
    val view: LinearLayout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val month = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); clearTime(this) }
    var selected: Calendar? = null
        private set
    private var collapsed = TaskStore.prefs(act).getBoolean("cal_collapsed", false)
    private var tasks: List<Task> = emptyList()

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    companion object {
        fun clearTime(c: Calendar) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        }

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        /** Termíny úkolů v intervalu <start, end) — opakované rozbalí na jednotlivé výskyty. */
        fun occurrences(tasks: List<Task>, start: Long, end: Long): List<Pair<Task, Long>> {
            val out = mutableListOf<Pair<Task, Long>>()
            for (t in tasks) {
                if (t.repeat.isEmpty() || t.done) {
                    if (t.eventMillis in start until end) out += t to t.eventMillis
                    continue
                }
                var at = t.eventMillis
                var guard = 0
                while (at < end && guard++ < 400) {
                    if (at >= start) out += t to at
                    at = Recurrence.next(at, t.repeat)
                }
            }
            return out.sortedBy { it.second }
        }
    }

    fun render(allTasks: List<Task>) {
        tasks = allTasks
        view.removeAllViews()
        view.addView(headerRow())
        if (!collapsed) view.addView(grid())
    }

    private fun headerRow(): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun arrow(t: String, delta: Int) = TextView(act).apply {
            text = t; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(color(R.color.teal))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { month.add(Calendar.MONTH, delta); collapsed = false; saveCollapsed(); render(tasks) }
        }
        row.addView(arrow("‹", -1))
        row.addView(TextView(act).apply {
            text = SimpleDateFormat("LLLL yyyy", cs).format(month.time).replaceFirstChar { it.uppercase() }
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            // klepnutí na název měsíce = zpět na dnešek
            setOnClickListener {
                val now = Calendar.getInstance()
                month.timeInMillis = now.timeInMillis; month.set(Calendar.DAY_OF_MONTH, 1); clearTime(month)
                render(tasks)
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(arrow("›", 1))
        row.addView(TextView(act).apply {
            text = if (collapsed) "▾" else "▴"
            textSize = 18f
            setTextColor(color(R.color.text2))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            contentDescription = if (collapsed) "Rozbalit kalendář" else "Sbalit kalendář"
            setOnClickListener { collapsed = !collapsed; saveCollapsed(); render(tasks) }
        })
        return row
    }

    private fun saveCollapsed() = TaskStore.prefs(act).edit().putBoolean("cal_collapsed", collapsed).apply()

    private fun grid(): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne").forEachIndexed { i, n ->
            head.addView(TextView(act).apply {
                text = n; gravity = Gravity.CENTER; textSize = 12f
                setTextColor(if (i >= 5) color(R.color.late) else color(R.color.text2))
                setPadding(0, dp(2), 0, dp(2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(head)

        val start = month.clone() as Calendar
        val shift = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7      // pondělí = 0
        start.add(Calendar.DAY_OF_MONTH, -shift)
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 42) }

        // počet úkolů po dnech pro celých 6 zobrazených týdnů
        val counts = HashMap<Int, Int>()
        val c = Calendar.getInstance()
        for ((_, at) in occurrences(tasks, start.timeInMillis, end.timeInMillis)) {
            c.timeInMillis = at
            val key = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
            counts[key] = (counts[key] ?: 0) + 1
        }

        val today = Calendar.getInstance()
        val curMonth = month.get(Calendar.MONTH)
        val cell = start.clone() as Calendar
        repeat(6) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                val d = cell.clone() as Calendar
                val n = counts[d.get(Calendar.YEAR) * 1000 + d.get(Calendar.DAY_OF_YEAR)] ?: 0
                row.addView(dayCell(d, d.get(Calendar.MONTH) == curMonth, today, n),
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                cell.add(Calendar.DAY_OF_MONTH, 1)
            }
            box.addView(row)
        }
        return box
    }

    private fun dayCell(d: Calendar, inMonth: Boolean, today: Calendar, count: Int): View {
        val isSel = selected?.let { sameDay(it, d) } == true
        val isToday = sameDay(d, today)
        val dow = d.get(Calendar.DAY_OF_WEEK)
        val holiday = Recurrence.isCzechHoliday(d)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(when {
                    isSel -> color(R.color.teal)
                    count > 0 -> color(R.color.teal_light)   // den s úkolem je podbarvený
                    inMonth -> Color.WHITE
                    else -> Color.TRANSPARENT
                })
                if (isToday && !isSel) setStroke(dp(2), color(R.color.teal))
            }
            alpha = if (inMonth) 1f else 0.4f
            setOnClickListener {
                if (isSel) {
                    selected = null
                } else {
                    selected = d
                    if (!inMonth) { month.timeInMillis = d.timeInMillis; month.set(Calendar.DAY_OF_MONTH, 1); clearTime(month) }
                }
                render(tasks)
                onDaySelected(selected)
            }
        }
        box.addView(TextView(act).apply {
            text = d.get(Calendar.DAY_OF_MONTH).toString()
            gravity = Gravity.CENTER
            textSize = 15f
            if (isToday || count > 0) setTypeface(typeface, Typeface.BOLD)
            setTextColor(when {
                isSel -> Color.WHITE
                holiday -> color(R.color.late)
                weekend -> color(R.color.text2)
                else -> color(R.color.text)
            })
        })
        box.addView(TextView(act).apply {
            text = if (count == 0) " " else "●".repeat(minOf(count, 3)) + if (count > 3) "+" else ""
            gravity = Gravity.CENTER
            textSize = 8f
            setTextColor(if (isSel) Color.WHITE else color(R.color.teal))
        })
        return box
    }
}
