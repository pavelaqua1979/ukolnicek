package cz.paul.ukolnicek

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pozvánky do kalendáře (iCalendar / .ics) — Outlook je zobrazí s tlačítkem Přijmout. */
object CalendarInvite {

    private fun utc(millis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    /** Místní čas (Europe/Prague) — kvůli opakování musí kalendář znát časové pásmo. */
    private fun local(millis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Prague") }
            .format(Date(millis))

    private val vtimezone = listOf(
        "BEGIN:VTIMEZONE", "TZID:Europe/Prague",
        "BEGIN:STANDARD", "DTSTART:19701025T030000", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
        "TZOFFSETFROM:+0200", "TZOFFSETTO:+0100", "TZNAME:CET", "END:STANDARD",
        "BEGIN:DAYLIGHT", "DTSTART:19700329T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
        "TZOFFSETFROM:+0100", "TZOFFSETTO:+0200", "TZNAME:CEST", "END:DAYLIGHT",
        "END:VTIMEZONE"
    )

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
        .replace(",", "\\,").replace("\n", "\\n")

    /** Řádky delší než 75 znaků se podle normy zalamují (pokračování začíná mezerou). */
    private fun fold(line: String): String {
        if (line.length <= 73) return line
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val end = minOf(line.length, i + 73)
            if (i > 0) sb.append("\r\n ")
            sb.append(line, i, end)
            i = end
        }
        return sb.toString()
    }

    fun uid(ctx: Context, taskId: Int) = "ukolnicek-$taskId-${TaskStore.installId(ctx)}@ukolnicek"

    fun build(
        ctx: Context, taskId: Int, title: String, eventMillis: Long, remindMillis: Long,
        method: String, organizer: String, attendee: String, repeat: String = ""
    ): String {
        val now = System.currentTimeMillis()
        val minutesBefore = ((eventMillis - remindMillis) / 60000).coerceAtLeast(0)
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "PRODID:-//Ukolnicek//CZ",
            "VERSION:2.0",
            "METHOD:$method"
        )
        lines += vtimezone
        lines += listOf(
            "BEGIN:VEVENT",
            "UID:${uid(ctx, taskId)}",
            // SEQUENCE musí s každou změnou růst — čas v sekundách roste vždy
            "SEQUENCE:${(now / 1000 - 1_700_000_000L)}",
            "DTSTAMP:${utc(now)}",
            "DTSTART;TZID=Europe/Prague:${local(eventMillis)}",
            "DTEND;TZID=Europe/Prague:${local(eventMillis + 30 * 60_000)}",
            "SUMMARY:${esc(title)}",
            "DESCRIPTION:${esc("Úkol z Úkolníčku")}",
            "ORGANIZER;CN=Úkolníček:mailto:$organizer",
            "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=FALSE:mailto:$attendee",
            "STATUS:${if (method == "CANCEL") "CANCELLED" else "CONFIRMED"}",
            "TRANSP:OPAQUE"
        )
        Recurrence.rrule(repeat)?.let { lines += "RRULE:$it" }
        if (method != "CANCEL") {
            lines += listOf(
                "BEGIN:VALARM",
                "TRIGGER:-PT${minutesBefore}M",
                "ACTION:DISPLAY",
                "DESCRIPTION:${esc(title)}",
                "END:VALARM"
            )
        }
        lines += listOf("END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    /** Naplánuje odeslání pozvánky (REQUEST) nebo zrušení (CANCEL) — počká na internet. */
    fun enqueue(ctx: Context, task: Task, method: String) {
        val req = OneTimeWorkRequestBuilder<CalendarWorker>()
            .setInputData(
                workDataOf(
                    "id" to task.id, "title" to task.title, "event" to task.eventMillis,
                    "remind" to task.remindMillis, "method" to method, "repeat" to task.repeat
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("cal_${task.id}", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

class CalendarWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val p = TaskStore.prefs(ctx)
        val to = Senders.calendarAddress(ctx)
        val from = p.getString(TaskStore.KEY_MAIL_USER, "").orEmpty().trim()
        if (to.isEmpty() || from.isEmpty()) return Result.failure()

        val id = inputData.getInt("id", -1)
        val title = inputData.getString("title").orEmpty()
        val event = inputData.getLong("event", 0)
        val remind = inputData.getLong("remind", event)
        val method = inputData.getString("method") ?: "REQUEST"
        val repeat = inputData.getString("repeat").orEmpty()

        val ics = CalendarInvite.build(ctx, id, title, event, remind, method, from, to, repeat)
        val subject = if (method == "CANCEL") "Zrušeno: $title" else title
        val body = if (method == "CANCEL") "Úkol byl zrušen: $title"
        else "$title\nKdy: ${Reminders.whenText(event)}\n\n— Úkolníček"

        val err = Senders.email(ctx, subject, body, ics, method, toOverride = to) ?: return Result.success()
        if (runAttemptCount < 3) return Result.retry()
        Reminders.showNotification(ctx, 200000 + id, "Pozvánku do kalendáře se nepodařilo odeslat", "$err\n\n$title")
        return Result.failure()
    }
}
