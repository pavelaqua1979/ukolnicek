package cz.paul.ukolnicek

import java.util.Calendar

/**
 * Z diktované věty vytáhne datum a čas, zbytek je text úkolu.
 * Např. "zítra v 8 zavolat do servisu" -> zítra 8:00, "Zavolat do servisu".
 * Umí: dnes, zítra, pozítří, v pondělí…v neděli, za X minut/hodin/dní,
 *      v 8, v 15:30, v 7.15, ráno, dopoledne, odpoledne, večer.
 */
object VoiceParser {

    data class Result(val title: String, val timeMillis: Long?)

    private const val END = "(?=[\\s,.!?]|$)"
    private const val START = "(?<![\\p{L}\\d])"

    private val days = mapOf(
        "pondělí" to Calendar.MONDAY, "úterý" to Calendar.TUESDAY, "středu" to Calendar.WEDNESDAY,
        "čtvrtek" to Calendar.THURSDAY, "pátek" to Calendar.FRIDAY, "sobotu" to Calendar.SATURDAY,
        "neděli" to Calendar.SUNDAY
    )

    fun parse(input: String, now: Calendar = Calendar.getInstance()): Result {
        var text = " " + input.trim() + " "
        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        var dateSet = false
        var timeSet = false
        var relative = false

        fun cut(r: Regex): MatchResult? {
            val m = r.find(text) ?: return null
            text = text.removeRange(m.range).let { " $it " }
            return m
        }

        // za X minut / hodin / dní
        cut(Regex("${START}za\\s+(\\d+|hodinu|minutu|den|týden)\\s*(minut[uy]?|min|hodin[uy]?|hod|dn[ůíy]|den|týdn[ůy]|týden)?$END", RegexOption.IGNORE_CASE))?.let { m ->
            val numRaw = m.groupValues[1].lowercase()
            val unitRaw = (m.groupValues[2].ifEmpty { numRaw }).lowercase()
            val n = numRaw.toIntOrNull() ?: 1
            when {
                unitRaw.startsWith("min") -> cal.add(Calendar.MINUTE, n)
                unitRaw.startsWith("hod") -> cal.add(Calendar.HOUR_OF_DAY, n)
                unitRaw.startsWith("týd") -> cal.add(Calendar.DAY_OF_YEAR, 7 * n)
                else -> cal.add(Calendar.DAY_OF_YEAR, n)
            }
            relative = true; dateSet = true; timeSet = true
        }

        // dnes / zítra / pozítří
        if (!relative) {
            cut(Regex("${START}(dnes|dneska|zítra|pozítří)$END", RegexOption.IGNORE_CASE))?.let { m ->
                when (m.groupValues[1].lowercase()) {
                    "zítra" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                    "pozítří" -> cal.add(Calendar.DAY_OF_YEAR, 2)
                }
                dateSet = true
            }
        }

        // v pondělí … v neděli (nejbližší další)
        if (!relative && !dateSet) {
            cut(Regex("${START}(?:v|ve)\\s+(pondělí|úterý|středu|čtvrtek|pátek|sobotu|neděli)$END", RegexOption.IGNORE_CASE))?.let { m ->
                val target = days.getValue(m.groupValues[1].lowercase())
                var diff = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0) diff = 7
                cal.add(Calendar.DAY_OF_YEAR, diff)
                dateSet = true
            }
        }

        // část dne
        var dayPart: String? = null
        cut(Regex("${START}(ráno|dopoledne|odpoledne|večer)$END", RegexOption.IGNORE_CASE))?.let {
            dayPart = it.groupValues[1].lowercase()
        }

        // v 8, v 15:30, ve 4, o 7.15
        if (!relative) {
            cut(Regex("${START}(?:v|ve|o)\\s+(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s*(?:hodin|hod|h))?$END", RegexOption.IGNORE_CASE))?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if ((dayPart == "odpoledne" || dayPart == "večer") && h < 12) h += 12
                if (h in 0..23 && min in 0..59) {
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                    timeSet = true
                }
            }
        }

        if (!timeSet && dayPart != null) {
            val h = when (dayPart) { "ráno" -> 7; "dopoledne" -> 10; "odpoledne" -> 14; else -> 19 }
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, 0)
            timeSet = true
        }

        if (!dateSet && !timeSet) return Result(clean(text), null)

        if (!timeSet) { cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0) }
        // jen čas, který už dnes proběhl -> zítra
        if (!dateSet && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)

        return Result(clean(text), cal.timeInMillis)
    }

    private fun clean(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        return t.replaceFirstChar { it.uppercase() }
    }
}
