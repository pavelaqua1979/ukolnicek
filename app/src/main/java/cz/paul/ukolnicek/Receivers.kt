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

/** Přijde v čas upomínky: hned ukáže notifikaci a odeslání WhatsApp/e-mailu předá na pozadí. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, -1)
        val task = TaskStore.get(ctx, id) ?: return
        if (task.done) return

        if (task.notify) Reminders.showNotification(ctx, task.id, "Upomínka: ${task.title}", Reminders.message(task))
        if (task.whatsapp) SendWorker.enqueue(ctx, task.id, SendWorker.WA)
        if (task.email) SendWorker.enqueue(ctx, task.id, SendWorker.MAIL)
    }
}

/** Odeslání přes síť. Když není internet, počká na něj; při chybě to zkusí znovu. */
class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val id = inputData.getInt("id", -1)
        val channel = inputData.getString("channel") ?: return Result.failure()
        val task = TaskStore.get(applicationContext, id) ?: return Result.success()
        if (task.done) return Result.success()

        val err = when (channel) {
            WA -> Senders.whatsApp(applicationContext, "⏰ Upomínka: ${Reminders.message(task)}")
            else -> Senders.email(
                applicationContext,
                "Upomínka: ${task.title}",
                Reminders.message(task) + "\n\n— Úkolníček"
            )
        } ?: return Result.success()

        if (runAttemptCount < 3) return Result.retry()
        // Po opakovaném neúspěchu aspoň notifikace, ať upomínka nezapadne
        Reminders.showNotification(
            applicationContext, 100000 + id,
            "Nepodařilo se odeslat: ${task.title}",
            "$err\n\n${Reminders.message(task)}"
        )
        return Result.failure()
    }

    companion object {
        const val WA = "wa"
        const val MAIL = "mail"

        fun enqueue(ctx: Context, id: Int, channel: String) {
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(workDataOf("id" to id, "channel" to channel))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("send_${id}_$channel", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Po restartu telefonu se budíky ztratí — tady se znovu naplánují. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
    }
}
