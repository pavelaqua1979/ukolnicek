package cz.paul.ukolnicek

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Budík:
 *  - KIND_REMIND: čas upomínky -> hned notifikace, WhatsApp/e-mail na pozadí; pak naplánuje další krok
 *  - KIND_ROLL:   opakovaný úkol, termín proběhl -> posune na další termín a naplánuje jeho upomínku
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, -1)
        val kind = intent.getStringExtra(Reminders.EXTRA_KIND) ?: Reminders.KIND_REMIND
        val task = TaskStore.get(ctx, id) ?: return
        if (task.done) return

        if (kind == Reminders.KIND_REMIND) {
            if (task.notify) Reminders.showNotification(ctx, task.id, "Upomínka: ${task.title}", Reminders.message(task))
            if (task.whatsapp) SendWorker.enqueue(ctx, task, SendWorker.WA)
            if (task.email) SendWorker.enqueue(ctx, task, SendWorker.MAIL)
        }
        // Opakovaný úkol: naplánuje posun / další upomínku; jednorázový: nic dalšího
        Reminders.schedule(ctx, task)
    }
}

/** Odeslání přes síť. Když není internet, počká na něj; při chybě to zkusí znovu. */
class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val id = inputData.getInt("id", -1)
        val channel = inputData.getString("channel") ?: return Result.failure()
        // Text se bere z doby upomínky — opakovaný úkol se mezitím mohl posunout na další termín
        val title = inputData.getString("title").orEmpty()
        val event = inputData.getLong("event", 0)
        val msg = Reminders.message(title, event)

        val err = when (channel) {
            WA -> Senders.whatsApp(applicationContext, "⏰ Upomínka: $msg")
            else -> Senders.email(applicationContext, "Upomínka: $title", "$msg\n\n— Úkolníček")
        } ?: return Result.success()

        if (runAttemptCount < 3) return Result.retry()
        // Po opakovaném neúspěchu aspoň notifikace, ať upomínka nezapadne
        Reminders.showNotification(applicationContext, 100000 + id, "Nepodařilo se odeslat: $title", "$err\n\n$msg")
        return Result.failure()
    }

    companion object {
        const val WA = "wa"
        const val MAIL = "mail"

        fun enqueue(ctx: Context, task: Task, channel: String) {
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(
                    workDataOf(
                        "id" to task.id, "channel" to channel,
                        "title" to task.title, "event" to task.eventMillis
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("send_${task.id}_$channel", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/** Po restartu telefonu se budíky ztratí — tady se znovu naplánují. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
    }
}
