package cz.paul.ukolnicek

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Reminders {
    const val CHANNEL_ID = "ukoly"
    const val EXTRA_ID = "task_id"
    const val EXTRA_KIND = "kind"
    const val KIND_REMIND = "remind"   // čas upomínky -> odeslat
    const val KIND_ROLL = "roll"       // opakovaný úkol: událost proběhla -> posunout na další termín

    private val fmt = SimpleDateFormat("EEE d. M. 'v' HH:mm", Locale("cs"))
    fun whenText(millis: Long): String = fmt.format(Date(millis))

    /** Text upomínky, stejný pro notifikaci, WhatsApp i e-mail. */
    fun message(title: String, eventMillis: Long) = "$title\nKdy: ${whenText(eventMillis)}"
    fun message(task: Task) = message(task.title, task.eventMillis)

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Upomínky úkolů", NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pending(ctx: Context, id: Int, kind: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Opakovaný úkol, jehož termín už proběhl, posune na nejbližší budoucí termín (a uloží).
     * Rozestup mezi upomínkou a událostí zůstává stejný.
     */
    fun roll(ctx: Context, t: Task): Task {
        val now = System.currentTimeMillis()
        if (t.repeat.isEmpty() || t.done || t.eventMillis > now) return t
        val offset = t.eventMillis - t.remindMillis
        var e = t.eventMillis
        var guard = 0
        while (e <= now && guard++ < 5000) e = Recurrence.next(e, t.repeat)
        val moved = t.copy(eventMillis = e, remindMillis = e - offset)
        TaskStore.upsert(ctx, moved)
        return moved
    }

    /** Opakovaný úkol odškrtnutý jako hotový -> přeskočí na další termín. */
    fun skipToNext(ctx: Context, t: Task): Task {
        val offset = t.eventMillis - t.remindMillis
        var e = Recurrence.next(t.eventMillis, t.repeat)
        val now = System.currentTimeMillis()
        var guard = 0
        while (e <= now && guard++ < 5000) e = Recurrence.next(e, t.repeat)
        val moved = t.copy(eventMillis = e, remindMillis = e - offset, done = false)
        TaskStore.upsert(ctx, moved)
        return moved
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            // Přesné budíky nepovoleny — použij méně přesný (může se zpozdit o pár minut)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun schedule(ctx: Context, task: Task) {
        cancel(ctx, task.id)
        if (task.done) return
        val t = roll(ctx, task)
        val now = System.currentTimeMillis()
        val hasChannels = t.notify || t.whatsapp || t.email
        when {
            hasChannels && t.remindMillis > now ->
                setAlarm(ctx, t.remindMillis, pending(ctx, t.id, KIND_REMIND))
            t.repeat.isNotEmpty() ->
                // upomínka tohoto termínu už byla -> po události posunout na další termín
                setAlarm(ctx, t.eventMillis + 1000, pending(ctx, t.id, KIND_ROLL))
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id, KIND_REMIND))
    }

    fun rescheduleAll(ctx: Context) = TaskStore.all(ctx).forEach { schedule(ctx, it) }

    fun showNotification(ctx: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { /* oprávnění k notifikacím zamítnuto */ }
    }
}
