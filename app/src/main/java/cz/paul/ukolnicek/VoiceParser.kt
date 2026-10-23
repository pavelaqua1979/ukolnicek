package cz.paul.ukolnicek

import java.util.Calendar

/**
 * Z diktované věty vytáhne datum a čas, zbytek je text úkolu.
 * Např. "zítra v 8 zavolat do servisu" -> zítra 8:00, "Zavolat do servisu".
 * Umí: dnes, zítra, pozítří, v pondělí…v neděli, za X minut/hodin/dní,
 *      v 8, v 15:30, v 7.15, ráno, dopoledne, odpoledne, večer.
 * Opakování: každý den, každý týden, každé pondělí, každé 2 týdny, každý měsíc, každý rok,
 *      každé první/druhé/třetí/čtvrté/poslední pondělí (v měsíci),
 *      (každý) první/poslední pracovní den (v měsíci).
 */
object VoiceParser {

    data class Result(val title: String, val timeMillis: Long?, val repeat: String = "")

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

        // ---------- Opakování (musí být první, ať „v pondělí“ nesebere datum) ----------
        val ci = RegexOption.IGNORE_CASE
        val dayNames = "pondělí|úterý|středu|čtvrtek|pátek|sobotu|neděli"
        var repeat = ""          // může obsahovat „?“ = doplní se z výsledného data
        cut(Regex("${START}(?:každ(?:ý|é|ou)\\s+)?(první|poslední)\\s+pracovní\\s+den(?:\\s+v\\s+měsíci)?$END", ci))?.let {
            repeat = if (it.groupValues[1].lowercase() == "první") "MWF" else "MWL"
        }
        if (repeat.isEmpty()) cut(Regex("${START}každ(?:ý|é|ou)\\s+(první|druh[éýo]u?|třetí|čtvrt[éýo]u?|poslední)\\s+($dayNames)(?:\\s+v\\s+měsíci)?$END", ci))?.let {
            val ord = it.groupValues[1].lowercase()
            val dow = days.getValue(it.groupValues[2].lowercase())
            val n = when {
                ord == "první" -> 1; ord.startsWith("druh") -> 2; ord == "třetí" -> 3
                ord.startsWith("čtvrt") -> 4; else -> 0
            }
            repeat = if (n == 0) "ML:$dow" else "MN:$n:$dow"
        }
        if (repeat.isEmpty()) cut(Regex("${START}každ(?:ý|é|ou)\\s+($dayNames)$END", ci))?.let {
            repeat = "W:" + days.getValue(it.groupValues[1].lowercase())
        }
        if (repeat.isEmpty()) cut(Regex("${START}(každé\\s+(?:2|dva)\\s+týdny|každý\\s+druhý\\s+týden)$END", ci))?.let { repeat = "W2:?" }
        if (repeat.isEmpty()) cut(Regex("${START}(každý\\s+den|denně)$END", ci))?.let { repeat = "D" }
        if (repeat.isEmpty()) cut(Regex("${START}(každý\\s+týden|týdně)$END", ci))?.let { repeat = "W:?" }
        if (repeat.isEmpty()) cut(Regex("${START}(každý\\s+měsíc|měsíčně)$END", ci))?.let { repeat = "M:?" }
        if (repeat.isEmpty()) cut(Regex("${START}(každý\\s+rok|ročně)$END", ci))?.let { repeat = "Y" }

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

        if (repeat.isEmpty() && !dateSet && !timeSet) return Result(clean(text), null)

        if (!timeSet) { cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0) }
        // jen čas, který už dnes proběhl -> zítra
        if (!dateSet && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)

        if (repeat.isNotEmpty()) {
            // doplnit pravidlo podle data („každý týden“ -> den v týdnu, „každý měsíc“ -> den v měsíci)
            repeat = repeat
                .replace("W:?", "W:" + cal.get(Calendar.DAY_OF_WEEK))
                .replace("W2:?", "W2:" + cal.get(Calendar.DAY_OF_WEEK))
                .replace("M:?", "M:" + cal.get(Calendar.DAY_OF_MONTH))
            // pravidla s pevným dnem (každé pondělí, poslední pondělí, pracovní den) určí datum sama
            val kind = Recurrence.kind(repeat)
            val t = if (kind in setOf("W", "MN", "ML", "MWF", "MWL") && !dateSet) {
                val from = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Recurrence.firstAtOrAfter(from, repeat, now.timeInMillis)
            } else cal.timeInMillis
            return Result(clean(text), t, repeat)
        }

        return Result(clean(text), cal.timeInMillis)
    }

    private fun clean(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        return t.replaceFirstChar { it.uppercase() }
    }
}
