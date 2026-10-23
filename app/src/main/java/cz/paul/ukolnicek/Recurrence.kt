package cz.paul.ukolnicek

import java.util.Calendar

/**
 * Pravidla opakování. Kód pravidla:
 *   ""          neopakovat
 *   "D"         každý den
 *   "W:dow"     každý týden (dow = Calendar.SUNDAY..SATURDAY)
 *   "W2:dow"    každé 2 týdny
 *   "M:den"     každý měsíc v daný den (31 -> poslední den kratších měsíců)
 *   "MN:n:dow"  každý měsíc n-tý den v týdnu (např. 2. úterý)
 *   "ML:dow"    každý měsíc poslední den v týdnu (např. poslední pondělí)
 *   "MWF"       každý měsíc první pracovní den (po–pá, bez českých svátků)
 *   "MWL"       každý měsíc poslední pracovní den
 *   "Y"         každý rok
 */
object Recurrence {

    // index = Calendar.DAY_OF_WEEK (1 = neděle)
    private val nom = arrayOf("", "neděle", "pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota")
    private val acc = arrayOf("", "neděli", "pondělí", "úterý", "středu", "čtvrtek", "pátek", "sobotu")
    private val ics = arrayOf("", "SU", "MO", "TU", "WE", "TH", "FR", "SA")

    /** „v pondělí“, „ve středu“, „ve čtvrtek“ */
    fun onDay(dow: Int) = (if (dow == Calendar.WEDNESDAY || dow == Calendar.THURSDAY) "ve " else "v ") + acc[dow]

    fun kind(code: String) = code.substringBefore(":")
    private fun args(code: String) = code.split(":").drop(1).map { it.toInt() }

    fun describe(code: String): String = when (kind(code)) {
        "" -> ""
        "D" -> "každý den"
        "W" -> "každý týden " + onDay(args(code)[0])
        "W2" -> "každé 2 týdny " + onDay(args(code)[0])
        "M" -> "každý měsíc ${args(code)[0]}. den"
        "MN" -> args(code).let { "každý měsíc, ${it[0]}. ${nom[it[1]]}" }
        "ML" -> "každý měsíc, poslední ${nom[args(code)[0]]}"
        "MWF" -> "první pracovní den v měsíci"
        "MWL" -> "poslední pracovní den v měsíci"
        "Y" -> "každý rok"
        else -> ""
    }

    /** n-tý (1–4) den v týdnu v měsíci, ve kterém je cal; čas zůstává. */
    fun setNthWeekday(cal: Calendar, n: Int, dow: Int) {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val diff = (dow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        val day = 1 + diff + (n - 1) * 7
        if (day > cal.getActualMaximum(Calendar.DAY_OF_MONTH)) setLastWeekday(cal, dow)
        else cal.set(Calendar.DAY_OF_MONTH, day)
    }

    /** Poslední daný den v týdnu v měsíci, ve kterém je cal. */
    fun setLastWeekday(cal: Calendar, dow: Int) {
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val diff = (cal.get(Calendar.DAY_OF_WEEK) - dow + 7) % 7
        cal.add(Calendar.DAY_OF_MONTH, -diff)
    }

    // ---------- Pracovní dny a české svátky ----------

    /** Velikonoční neděle (gregoriánský kalendář) -> (měsíc 1–12, den) */
    private fun easterSunday(y: Int): Pair<Int, Int> {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return month to day
    }

    fun isCzechHoliday(c: Calendar): Boolean {
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        val fixed = setOf(1 to 1, 5 to 1, 5 to 8, 7 to 5, 7 to 6, 9 to 28, 10 to 28, 11 to 17, 12 to 24, 12 to 25, 12 to 26)
        if ((m to d) in fixed) return true
        val (em, ed) = easterSunday(c.get(Calendar.YEAR))
        val easter = (c.clone() as Calendar).apply { set(Calendar.MONTH, em - 1); set(Calendar.DAY_OF_MONTH, ed) }
        val goodFriday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -2) }
        val easterMonday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun same(x: Calendar) = x.get(Calendar.MONTH) + 1 == m && x.get(Calendar.DAY_OF_MONTH) == d
        return same(goodFriday) || same(easterMonday)
    }

    fun isWorkday(c: Calendar): Boolean {
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && !isCzechHoliday(c)
    }

    fun setFirstWorkday(c: Calendar) {
        c.set(Calendar.DAY_OF_MONTH, 1)
        while (!isWorkday(c)) c.add(Calendar.DAY_OF_MONTH, 1)
    }

    fun setLastWorkday(c: Calendar) {
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        while (!isWorkday(c)) c.add(Calendar.DAY_OF_MONTH, -1)
    }

    /** Další výskyt po zadaném termínu. */
    fun next(millis: Long, code: String): Long {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        when (kind(code)) {
            "D" -> c.add(Calendar.DAY_OF_YEAR, 1)
            "W" -> c.add(Calendar.DAY_OF_YEAR, 7)
            "W2" -> c.add(Calendar.DAY_OF_YEAR, 14)
            "Y" -> c.add(Calendar.YEAR, 1)
            "M" -> {
                c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1)
                c.set(Calendar.DAY_OF_MONTH, minOf(args(code)[0], c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            "MN" -> {
                c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1)
                args(code).let { setNthWeekday(c, it[0], it[1]) }
            }
            "ML" -> {
                c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1)
                setLastWeekday(c, args(code)[0])
            }
            "MWF" -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1); setFirstWorkday(c) }
            "MWL" -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, 1); setLastWorkday(c) }
            else -> return millis
        }
        return c.timeInMillis
    }

    /**
     * První výskyt pravidla nejdřív v čase `from` (datum + čas berou se z `from`).
     * Pro MN/ML hledá v aktuálním měsíci, pro W ve stejném týdnu.
     */
    fun firstAtOrAfter(from: Calendar, code: String, now: Long): Long {
        val c = from.clone() as Calendar
        when (kind(code)) {
            "MN" -> args(code).let { setNthWeekday(c, it[0], it[1]) }
            "ML" -> setLastWeekday(c, args(code)[0])
            "MWF" -> setFirstWorkday(c)
            "MWL" -> setLastWorkday(c)
            "W", "W2" -> {
                val dow = args(code)[0]
                c.add(Calendar.DAY_OF_YEAR, (dow - c.get(Calendar.DAY_OF_WEEK) + 7) % 7)
            }
        }
        var t = c.timeInMillis
        var guard = 0
        while (t <= now && guard++ < 1000) t = next(t, code)
        return t
    }

    /** Nabídka pravidel podle zvoleného data události (popisek, kód). */
    fun options(eventMillis: Long): List<Pair<String, String>> {
        val c = Calendar.getInstance().apply { timeInMillis = eventMillis }
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val day = c.get(Calendar.DAY_OF_MONTH)
        val n = (day - 1) / 7 + 1
        val isLast = day + 7 > c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val list = mutableListOf(
            "Neopakovat" to "",
            "Každý den" to "D",
            "Každý týden ${onDay(dow)}" to "W:$dow",
            "Každé 2 týdny ${onDay(dow)}" to "W2:$dow",
            "Každý měsíc $day. den" to "M:$day"
        )
        if (n <= 4) list += "Každý měsíc – $n. ${nom[dow]}" to "MN:$n:$dow"
        if (isLast) list += "Každý měsíc – poslední ${nom[dow]}" to "ML:$dow"
        list += "Každý měsíc – první pracovní den" to "MWF"
        list += "Každý měsíc – poslední pracovní den" to "MWL"
        list += "Každý rok $day. ${c.get(Calendar.MONTH) + 1}." to "Y"
        return list
    }

    /** Pravidlo ve formátu iCalendar (pro Outlook), nebo null. */
    fun rrule(code: String): String? = when (kind(code)) {
        "D" -> "FREQ=DAILY"
        "W" -> "FREQ=WEEKLY;BYDAY=${ics[args(code)[0]]}"
        "W2" -> "FREQ=WEEKLY;INTERVAL=2;BYDAY=${ics[args(code)[0]]}"
        "M" -> "FREQ=MONTHLY;BYMONTHDAY=${args(code)[0]}"
        "MN" -> args(code).let { "FREQ=MONTHLY;BYDAY=${it[0]}${ics[it[1]]}" }
        "ML" -> "FREQ=MONTHLY;BYDAY=-1${ics[args(code)[0]]}"
        // Outlook svátky nezná: v kalendáři to bude první/poslední den po–pá
        "MWF" -> "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1"
        "MWL" -> "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"
        "Y" -> "FREQ=YEARLY"
        else -> null
    }
}
