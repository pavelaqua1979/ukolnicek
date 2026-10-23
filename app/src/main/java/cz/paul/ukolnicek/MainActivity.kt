package cz.paul.ukolnicek

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val cs = Locale("cs")
    private val fmtDate = SimpleDateFormat("EEE d. M. yyyy", cs)
    private val fmtShort = SimpleDateFormat("EEE d. M.", cs)
    private val fmtTime = SimpleDateFormat("HH:mm", cs)
    private var tasks = mutableListOf<Task>()
    private lateinit var adapter: TaskAdapter

    /** Volby „Kdy upozornit“: popis + kolik minut před událostí (-1 = vlastní datum a čas). */
    private val remindOptions = listOf(
        "V čas události" to 0L,
        "5 minut předem" to 5L,
        "15 minut předem" to 15L,
        "30 minut předem" to 30L,
        "1 hodinu předem" to 60L,
        "2 hodiny předem" to 120L,
        "1 den předem" to 1440L,
        "2 dny předem" to 2880L,
        "1 týden předem" to 10080L,
        "Vlastní datum a čas" to -1L
    )
    private val customIndex get() = remindOptions.lastIndex

    // ---------- Hlasový vstup ----------
    private var onSpeech: ((String) -> Unit)? = null

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) onSpeech?.invoke(text)
        onSpeech = null
    }

    private fun listen(callback: (String) -> Unit) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Řekni úkol, např. „zítra v 8 zavolat do servisu“")
        onSpeech = callback
        try {
            speechLauncher.launch(i)
        } catch (_: ActivityNotFoundException) {
            onSpeech = null
            Toast.makeText(this, "V telefonu chybí rozpoznávání řeči (aplikace Google)", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Životní cyklus ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "Úkolníček"
        Reminders.createChannel(this)
        askNotificationPermission()

        adapter = TaskAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> editTask(tasks[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(tasks[pos]); true }
        findViewById<FloatingActionButton>(R.id.add).setOnClickListener { editTask(null) }
        findViewById<FloatingActionButton>(R.id.voice).setOnClickListener {
            listen { spoken -> editTask(null, VoiceParser.parse(spoken)) }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        tasks = TaskStore.all(this)
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---------- Výběr data a času ----------

    private fun pickDate(cal: Calendar, after: () -> Unit) {
        DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); after() },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime(cal: Calendar, after: () -> Unit) {
        TimePickerDialog(this, { _, h, min ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); after()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    // ---------- Přidání / úprava ----------

    private fun editTask(existing: Task?, voice: VoiceParser.Result? = null) {
        val v = layoutInflater.inflate(R.layout.dialog_task, null)
        val etTitle = v.findViewById<EditText>(R.id.title)
        val btnMic = v.findViewById<ImageButton>(R.id.mic)
        val btnDate = v.findViewById<Button>(R.id.date)
        val btnTime = v.findViewById<Button>(R.id.time)
        val spMode = v.findViewById<Spinner>(R.id.remindMode)
        val customRow = v.findViewById<View>(R.id.customRow)
        val btnRDate = v.findViewById<Button>(R.id.rdate)
        val btnRTime = v.findViewById<Button>(R.id.rtime)
        val tvInfo = v.findViewById<TextView>(R.id.remindInfo)
        val cbNotify = v.findViewById<CheckBox>(R.id.notify)
        val cbWa = v.findViewById<CheckBox>(R.id.wa)
        val cbMail = v.findViewById<CheckBox>(R.id.mail)
        val prefs = TaskStore.prefs(this)

        val ev = Calendar.getInstance()   // kdy to nastane
        val rc = Calendar.getInstance()   // vlastní čas upomínky
        var modeIndex: Int

        if (existing != null) {
            ev.timeInMillis = existing.eventMillis
            rc.timeInMillis = existing.remindMillis
            etTitle.setText(existing.title)
            cbNotify.isChecked = existing.notify
            cbWa.isChecked = existing.whatsapp
            cbMail.isChecked = existing.email
            val diffMin = (existing.eventMillis - existing.remindMillis) / 60000
            modeIndex = remindOptions.indexOfFirst { it.second == diffMin }.let { if (it < 0) customIndex else it }
        } else {
            ev.add(Calendar.HOUR_OF_DAY, 2)
            ev.set(Calendar.MINUTE, 0)
            modeIndex = prefs.getInt(TaskStore.KEY_LAST_REMIND_MODE, 4).coerceIn(0, customIndex)
            if (modeIndex == customIndex) modeIndex = 4
        }
        if (voice != null) {
            etTitle.setText(voice.title)
            voice.timeMillis?.let { ev.timeInMillis = it }
        }
        ev.set(Calendar.SECOND, 0); ev.set(Calendar.MILLISECOND, 0)
        rc.set(Calendar.SECOND, 0); rc.set(Calendar.MILLISECOND, 0)

        fun remindMillis(): Long {
            val off = remindOptions[modeIndex].second
            return if (off < 0) rc.timeInMillis else ev.timeInMillis - off * 60000
        }

        fun updateUi() {
            btnDate.text = fmtDate.format(ev.time)
            btnTime.text = fmtTime.format(ev.time)
            btnRDate.text = fmtDate.format(rc.time)
            btnRTime.text = fmtTime.format(rc.time)
            customRow.visibility = if (modeIndex == customIndex) View.VISIBLE else View.GONE
            val r = remindMillis()
            tvInfo.text = when {
                r <= System.currentTimeMillis() -> "⚠ Čas upomínky už proběhl"
                r > ev.timeInMillis -> "Upomínka: ${Reminders.whenText(r)} (až po události)"
                else -> "Upomínka: ${Reminders.whenText(r)}"
            }
        }

        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, remindOptions.map { it.first })
        spMode.setSelection(modeIndex)
        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == customIndex && modeIndex != customIndex) {
                    // Vlastní: předvyplň z aktuálně vypočtené upomínky
                    rc.timeInMillis = remindMillis()
                }
                modeIndex = pos
                updateUi()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateUi()

        btnDate.setOnClickListener { pickDate(ev) { updateUi() } }
        btnTime.setOnClickListener { pickTime(ev) { updateUi() } }
        btnRDate.setOnClickListener { pickDate(rc) { updateUi() } }
        btnRTime.setOnClickListener { pickTime(rc) { updateUi() } }

        // Diktování přímo v dialogu: text se doplní, čas události se nastaví, pokud zazněl
        btnMic.setOnClickListener {
            listen { spoken ->
                val r = VoiceParser.parse(spoken)
                val current = etTitle.text.toString().trim()
                etTitle.setText(if (current.isEmpty()) r.title else "$current ${r.title.replaceFirstChar { it.lowercase() }}")
                etTitle.setSelection(etTitle.text.length)
                r.timeMillis?.let { ev.timeInMillis = it; updateUi() }
            }
        }

        cbWa.setOnCheckedChangeListener { _, checked ->
            if (checked && !Senders.waConfigured(this))
                Toast.makeText(this, "Nejdřív nastav WhatsApp v menu ⋮ → Nastavení", Toast.LENGTH_LONG).show()
        }
        cbMail.setOnCheckedChangeListener { _, checked ->
            if (checked && !Senders.mailConfigured(this))
                Toast.makeText(this, "Nejdřív nastav e-mail v menu ⋮ → Nastavení", Toast.LENGTH_LONG).show()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nový úkol" else "Upravit úkol")
            .setView(v)
            .setPositiveButton("Uložit", null)
            .setNegativeButton("Zrušit", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    etTitle.error = "Zadej úkol"; return@setOnClickListener
                }
                val task = Task(
                    id = existing?.id ?: TaskStore.nextId(this),
                    title = title,
                    eventMillis = ev.timeInMillis,
                    remindMillis = remindMillis(),
                    notify = cbNotify.isChecked,
                    whatsapp = cbWa.isChecked,
                    email = cbMail.isChecked,
                    done = false
                )
                TaskStore.upsert(this, task)
                Reminders.schedule(this, task)
                prefs.edit().putInt(TaskStore.KEY_LAST_REMIND_MODE, modeIndex).apply()
                when {
                    !task.notify && !task.whatsapp && !task.email ->
                        Toast.makeText(this, "Uloženo bez upozornění", Toast.LENGTH_SHORT).show()
                    task.remindMillis <= System.currentTimeMillis() ->
                        Toast.makeText(this, "Čas upomínky už proběhl — upomínka nepřijde", Toast.LENGTH_LONG).show()
                }
                dlg.dismiss()
                refresh()
            }
        }
        dlg.show()
    }

    private fun confirmDelete(task: Task) {
        AlertDialog.Builder(this)
            .setTitle("Smazat úkol?")
            .setMessage(task.title)
            .setPositiveButton("Smazat") { _, _ ->
                Reminders.cancel(this, task.id)
                TaskStore.delete(this, task.id)
                refresh()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun toggleDone(task: Task, done: Boolean) {
        val t = task.copy(done = done)
        TaskStore.upsert(this, t)
        Reminders.schedule(this, t) // hotový se zruší, znovu otevřený naplánuje
        refresh()
    }

    // ---------- Nastavení ----------

    private fun openSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val p = TaskStore.prefs(this)
        val fields = mapOf(
            R.id.phone to TaskStore.KEY_WA_PHONE,
            R.id.apikey to TaskStore.KEY_WA_APIKEY,
            R.id.mailTo to TaskStore.KEY_MAIL_TO,
            R.id.mailUser to TaskStore.KEY_MAIL_USER,
            R.id.mailPass to TaskStore.KEY_MAIL_PASS,
            R.id.mailHost to TaskStore.KEY_MAIL_HOST
        ).mapKeys { v.findViewById<EditText>(it.key) }
        fields.forEach { (et, key) -> et.setText(p.getString(key, "")) }

        fun save() {
            val e = p.edit()
            fields.forEach { (et, key) -> e.putString(key, et.text.toString().trim()) }
            e.apply()
        }

        fun test(label: String, action: () -> String?) {
            save()
            Toast.makeText(this, "Odesílám $label…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = action()
                runOnUiThread {
                    Toast.makeText(this, err ?: "Odesláno, zkontroluj $label", Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        v.findViewById<Button>(R.id.testWa).setOnClickListener {
            test("WhatsApp") { Senders.whatsApp(this, "✅ Úkolníček: test WhatsApp funguje") }
        }
        v.findViewById<Button>(R.id.testMail).setOnClickListener {
            test("e-mail") { Senders.email(this, "Úkolníček: test", "✅ Test e-mailu funguje.") }
        }

        AlertDialog.Builder(this)
            .setTitle("Nastavení upozornění")
            .setView(v)
            .setPositiveButton("Uložit") { _, _ -> save() }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Nastavení (WhatsApp, e-mail)")
        menu.add(0, 2, 0, "Smazat hotové")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> openSettings()
            2 -> {
                tasks.filter { it.done }.forEach { TaskStore.delete(this, it.id) }
                refresh()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Seznam ----------

    inner class TaskAdapter : BaseAdapter() {
        override fun getCount() = tasks.size
        override fun getItem(position: Int) = tasks[position]
        override fun getItemId(position: Int) = tasks[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task, parent, false)
            val t = tasks[position]
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.sub)
            val sub2 = v.findViewById<TextView>(R.id.sub2)
            val cb = v.findViewById<CheckBox>(R.id.done)

            title.text = t.title
            title.paintFlags = if (t.done) title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            v.alpha = if (t.done) 0.5f else 1f

            val e = Date(t.eventMillis)
            val late = !t.done && t.eventMillis < System.currentTimeMillis()
            sub.text = "📅 ${fmtShort.format(e)} ${fmtTime.format(e)}" + if (late) "  · po termínu" else ""

            val icons = buildString {
                if (t.notify) append(" 🔔")
                if (t.whatsapp) append(" 💬")
                if (t.email) append(" ✉️")
            }
            sub2.text = if (icons.isEmpty()) "bez upozornění" else {
                val r = Date(t.remindMillis)
                "upomínka ${fmtShort.format(r)} ${fmtTime.format(r)} $icons"
            }

            cb.setOnCheckedChangeListener(null)
            cb.isChecked = t.done
            cb.setOnCheckedChangeListener { _, checked -> toggleDone(t, checked) }
            return v
        }
    }
}
