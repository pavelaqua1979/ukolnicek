package cz.paul.ukolnicek

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Task(
    val id: Int,
    val title: String,
    /** Kdy to nastane */
    val eventMillis: Long,
    /** Kdy přijde upomínka */
    val remindMillis: Long,
    val notify: Boolean,
    val whatsapp: Boolean,
    val email: Boolean,
    val done: Boolean = false
)

/** Jednoduché úložiště úkolů a nastavení v SharedPreferences (JSON). */
object TaskStore {
    private const val PREFS = "ukoly"
    private const val KEY_TASKS = "tasks"
    private const val KEY_NEXT_ID = "next_id"

    const val KEY_WA_PHONE = "wa_phone"
    const val KEY_WA_APIKEY = "wa_apikey"
    const val KEY_MAIL_TO = "mail_to"
    const val KEY_MAIL_USER = "mail_user"
    const val KEY_MAIL_PASS = "mail_pass"
    const val KEY_MAIL_HOST = "mail_host"
    const val KEY_LAST_REMIND_MODE = "last_remind_mode"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<Task> {
        val arr = JSONArray(prefs(ctx).getString(KEY_TASKS, "[]"))
        val list = mutableListOf<Task>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // "time" = formát z verze 1.0
            val event = if (o.has("event")) o.getLong("event") else o.optLong("time")
            list += Task(
                id = o.getInt("id"),
                title = o.getString("title"),
                eventMillis = event,
                remindMillis = o.optLong("remind", event),
                notify = o.optBoolean("notify", true),
                whatsapp = o.optBoolean("wa"),
                email = o.optBoolean("mail"),
                done = o.optBoolean("done")
            )
        }
        return list.sortedWith(compareBy<Task> { it.done }.thenBy { it.eventMillis }).toMutableList()
    }

    private fun save(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("title", it.title)
                put("event", it.eventMillis); put("remind", it.remindMillis)
                put("notify", it.notify); put("wa", it.whatsapp); put("mail", it.email)
                put("done", it.done)
            })
        }
        prefs(ctx).edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    fun nextId(ctx: Context): Int {
        val p = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, task: Task) {
        val list = all(ctx).filter { it.id != task.id }.toMutableList()
        list += task
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Int) = save(ctx, all(ctx).filter { it.id != id })
}
