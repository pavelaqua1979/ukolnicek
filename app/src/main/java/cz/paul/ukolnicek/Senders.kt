package cz.paul.ukolnicek

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import javax.activation.DataHandler

/** Odesílání upomínek mimo telefon. Každá funkce vrací text chyby, nebo null při úspěchu. */
object Senders {

    /** WhatsApp zpráva sám sobě přes CallMeBot. */
    fun whatsApp(ctx: Context, text: String): String? {
        val p = TaskStore.prefs(ctx)
        // CallMeBot chce jen číslice (bez „+“, mezer a pomlček)
        val phone = p.getString(TaskStore.KEY_WA_PHONE, "").orEmpty().filter { it.isDigit() }
        val key = p.getString(TaskStore.KEY_WA_APIKEY, "").orEmpty().trim()
        if (phone.isEmpty() || key.isEmpty()) return "WhatsApp: chybí číslo nebo API klíč v nastavení"
        return try {
            val url = "https://api.callmebot.com/whatsapp.php?phone=" +
                URLEncoder.encode(phone, "UTF-8") +
                "&text=" + URLEncoder.encode(text, "UTF-8") +
                "&apikey=" + URLEncoder.encode(key, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 20000
            val code = c.responseCode
            val body = try {
                (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (_: Exception) { "" }
            c.disconnect()
            // CallMeBot vrací chyby často i s kódem 200 — poznáme je podle textu odpovědi
            val plain = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
            val bad = listOf("invalid", "not activated", "paused", "wrong", "blocked")
                .any { plain.contains(it, ignoreCase = true) }
            when {
                code !in 200..299 -> "WhatsApp: server vrátil kód $code ${plain.take(150)}"
                bad -> "WhatsApp: ${plain.take(200)}"
                else -> null
            }
        } catch (e: Exception) {
            "WhatsApp: " + (e.message ?: "chyba sítě")
        }
    }

    /** Adresa pro pozvánky do kalendáře; když není vyplněná, použije se adresa pro upomínky. */
    fun calendarAddress(ctx: Context): String {
        val p = TaskStore.prefs(ctx)
        return p.getString(TaskStore.KEY_CAL_TO, "").orEmpty().trim()
            .ifEmpty { p.getString(TaskStore.KEY_MAIL_TO, "").orEmpty().trim() }
    }

    fun mailConfigured(ctx: Context): Boolean {
        val p = TaskStore.prefs(ctx)
        return listOf(TaskStore.KEY_MAIL_TO, TaskStore.KEY_MAIL_USER, TaskStore.KEY_MAIL_PASS)
            .all { !p.getString(it, "").isNullOrBlank() }
    }

    fun waConfigured(ctx: Context): Boolean {
        val p = TaskStore.prefs(ctx)
        return !p.getString(TaskStore.KEY_WA_PHONE, "").isNullOrBlank() &&
            !p.getString(TaskStore.KEY_WA_APIKEY, "").isNullOrBlank()
    }

    /** E-mail na přednastavenou adresu přes SMTP (výchozí Gmail, SSL port 465). */
    fun email(
        ctx: Context, subject: String, body: String,
        ics: String? = null, icsMethod: String = "REQUEST", toOverride: String? = null
    ): String? {
        val p = TaskStore.prefs(ctx)
        val to = toOverride?.trim()?.ifEmpty { null } ?: p.getString(TaskStore.KEY_MAIL_TO, "").orEmpty().trim()
        val user = p.getString(TaskStore.KEY_MAIL_USER, "").orEmpty().trim()
        val pass = p.getString(TaskStore.KEY_MAIL_PASS, "").orEmpty().replace(" ", "")
        val host = p.getString(TaskStore.KEY_MAIL_HOST, "").orEmpty().trim().ifEmpty { "smtp.gmail.com" }
        if (to.isEmpty() || user.isEmpty() || pass.isEmpty()) return "E-mail: chybí nastavení"

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", "465")
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(user, "Úkolníček", "UTF-8"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject, "UTF-8")
                if (ics == null) {
                    setText(body, "UTF-8")
                } else {
                    // Pozvánka: Outlook ji pozná podle části text/calendar; method=REQUEST/CANCEL
                    val text = MimeBodyPart().apply { setText(body, "UTF-8") }
                    val cal = MimeBodyPart().apply {
                        // přes DataHandler — JavaMail sám neumí převést String na text/calendar
                        dataHandler = DataHandler(
                            ByteArrayDataSource(ics.toByteArray(Charsets.UTF_8), "text/calendar; charset=UTF-8; method=$icsMethod")
                        )
                        setHeader("Content-Class", "urn:content-classes:calendarmessage")
                    }
                    setContent(MimeMultipart("alternative").apply { addBodyPart(text); addBodyPart(cal) })
                }
            }
            Transport.send(msg)
            null
        } catch (e: Exception) {
            "E-mail: " + (e.message ?: e.javaClass.simpleName)
        }
    }
}
