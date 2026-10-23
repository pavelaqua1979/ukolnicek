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

    private val fmt = SimpleDateFormat("EEE d. M. 'v' HH:mm", Locale("cs"))
    fun whenText(millis: Long): String = fmt.format(Date(millis))

    /** Text upomínky, stejný pro notifikaci, WhatsApp i e-mail. */
    fun message(task: Task) = "${task.title}\nKdy: ${whenText(task.eventMillis)}"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Upomínky úkolů", NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, task: Task) {
        cancel(ctx, task.id)
        if (task.done || task.remindMillis <= System.currentTimeMillis()) return
        if (!task.notify && !task.whatsapp && !task.email) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, task.id)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.remindMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.remindMillis, pi)
            }
        } catch (_: SecurityException) {
            // Přesné budíky nepovoleny — použij méně přesný (může se zpozdit o pár minut)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.remindMillis, pi)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
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
