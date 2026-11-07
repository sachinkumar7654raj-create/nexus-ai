package com.nexus.ai.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nexus.ai.R
import com.nexus.ai.service.ReminderReceiver
import com.nexus.ai.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** repeat: 0 = once, 1 = daily, 2 = weekly, 3 = yearly */
data class Reminder(val id: Int, val text: String, val whenMs: Long, val repeat: Int)

data class ParsedReminder(val whenMs: Long, val message: String, val repeat: Int)

object Reminders {
    private const val CHANNEL = "nexus_reminders"

    private fun file(ctx: Context) = File(ctx.filesDir, "reminders.json")

    fun all(ctx: Context): List<Reminder> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Reminder(o.getInt("id"), o.getString("t"), o.getLong("w"), o.optInt("r"))
            }.sortedBy { it.whenMs }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("w", it.whenMs).put("r", it.repeat)) }
            file(ctx).writeText(a.toString())
        } catch (_: Exception) {}
    }

    fun add(ctx: Context, text: String, whenMs: Long, repeat: Int): Reminder {
        val list = all(ctx)
        val r = Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, text, whenMs, repeat)
        save(ctx, list + r)
        schedule(ctx, r)
        return r
    }

    fun remove(ctx: Context, id: Int) {
        all(ctx).firstOrNull { it.id == id }?.let { cancelAlarm(ctx, it) }
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun clear(ctx: Context) {
        all(ctx).forEach { cancelAlarm(ctx, it) }
        save(ctx, emptyList())
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, r.id, Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canUseExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, r)
        try {
            if (canUseExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.whenMs, pi)
        }
    }

    private fun cancelAlarm(ctx: Context, r: Reminder) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, r))
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        all(ctx).forEach { r ->
            if (r.whenMs > now) schedule(ctx, r)
            else if (r.repeat != 0) { val n = r.copy(whenMs = nextTime(r, now)); update(ctx, n); schedule(ctx, n) }
        }
    }

    private fun update(ctx: Context, r: Reminder) = save(ctx, all(ctx).map { if (it.id == r.id) r else it })

    private fun nextTime(r: Reminder, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = r.whenMs }
        while (c.timeInMillis <= now) {
            when (r.repeat) {
                1 -> c.add(Calendar.DAY_OF_YEAR, 1)
                2 -> c.add(Calendar.WEEK_OF_YEAR, 1)
                3 -> c.add(Calendar.YEAR, 1)
                else -> return c.timeInMillis
            }
        }
        return c.timeInMillis
    }

    /** Called by the alarm: show a notification (and speak it if the app process is alive and speech is on). */
    fun fire(ctx: Context, id: Int) {
        val r = all(ctx).firstOrNull { it.id == id } ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle("NEXUS Reminder")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(10_000 + r.id, n) } catch (_: SecurityException) {}
        try { if (Assistant.prefs.speakReplies) Assistant.voice.speak("Reminder: ${r.text}") } catch (_: Exception) {}
        if (r.repeat != 0) {
            val next = r.copy(whenMs = nextTime(r, System.currentTimeMillis()))
            update(ctx, next)
            schedule(ctx, next)
        } else {
            save(ctx, all(ctx).filter { it.id != id })
        }
    }

    fun describe(r: Reminder): String {
        val f = SimpleDateFormat("EEE d MMM, hh:mm a", Locale.ENGLISH).format(Date(r.whenMs))
        val rep = when (r.repeat) { 1 -> " (roz)"; 2 -> " (har hafte)"; 3 -> " (har saal)"; else -> "" }
        return "$f$rep — ${r.text}"
    }

    // ------------------------------------------------------------------ parsing

    private val relRe = Regex("(\\d+)\\s*(second|sec|सेकंड|minute|min|mint|मिनट|hour|hr|ghante|ghanta|घंटे|घंटा|din|day|दिन)s?")
    private val dateRe = Regex("(\\d{1,2})\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val absRe = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?|baje|बजे)")
    private val fillers = setOf(
        "mujhe", "mujhko", "mereko", "mera", "meri", "ko", "ka", "ki", "ke", "remind", "reminder", "me", "to", "that",
        "yaad", "dilana", "dilao", "dila", "do", "de", "dena", "karo", "kar", "set", "laga", "lagao", "lagana", "baad", "mein", "in",
        "after", "later", "kal", "aaj", "parso", "subah", "shaam", "raat", "dopahar", "roz", "daily", "har", "din", "hafte", "saal",
        "weekly", "yearly", "every", "tomorrow", "today", "morning", "evening", "night", "afternoon", "please", "plz", "hey", "nexus",
        "baje", "at", "bhi", "ek", "a", "an", "the",
        "मुझे", "को", "का", "की", "के", "याद", "दिलाना", "दिलाओ", "दिला", "दो", "करो", "कर", "कल", "आज", "परसों", "सुबह", "शाम", "रात",
        "दोपहर", "रोज", "बजे", "बाद", "में", "रिमाइंडर", "नेक्सस", "हर", "दिन", "सेट"
    )

    fun parse(raw: String, now: Long = System.currentTimeMillis()): ParsedReminder? {
        var t = raw.lowercase(Locale.ROOT)
        val repeat = when {
            Regex("roz|daily|har din|हर दिन|रोज|everyday|every day").containsMatchIn(t) -> 1
            Regex("har hafte|weekly|हर हफ्ते|every week").containsMatchIn(t) -> 2
            Regex("har saal|yearly|हर साल|every year|birthday|जन्मदिन").containsMatchIn(t) -> 3
            else -> 0
        }
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var whenMs: Long

        val rel = relRe.find(t)
        val abs = absRe.find(t)
        val dateM = dateRe.find(t)
        if (dateM != null && rel == null) {
            // a calendar date such as "12 march" (birthdays, anniversaries); time defaults to 9:00 if not given
            val day = dateM.groupValues[1].toInt()
            val mon = months.indexOf(dateM.groupValues[2])
            var h = 9
            var min = 0
            if (abs != null) {
                h = abs.groupValues[1].toInt()
                min = abs.groupValues[2].ifEmpty { "0" }.toInt()
                val isPm = abs.groupValues[3].startsWith("p") || Regex("shaam|शाम|raat|रात|evening|night").containsMatchIn(t)
                if (isPm && h < 12) h += 12
            }
            if (day !in 1..31 || h > 23 || min > 59) return null
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.MONTH, mon)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, min)
            if (cal.timeInMillis <= now) cal.add(Calendar.YEAR, 1)
            whenMs = cal.timeInMillis
            t = t.replace(dateM.value, " ")
            if (abs != null) t = t.replace(abs.value, " ")
        } else if (rel != null && (abs == null || rel.range.first < abs.range.first)) {
            val n = rel.groupValues[1].toInt()
            val u = rel.groupValues[2]
            when {
                u.startsWith("sec") || u.startsWith("से") -> cal.add(Calendar.SECOND, n)
                u.startsWith("h") || u.startsWith("g") || u.startsWith("घं") -> cal.add(Calendar.HOUR_OF_DAY, n)
                u.startsWith("d") || u.startsWith("दि") -> cal.add(Calendar.DAY_OF_YEAR, n)
                else -> cal.add(Calendar.MINUTE, n)
            }
            whenMs = cal.timeInMillis
            t = t.removeRange(rel.range)
        } else if (abs != null) {
            var h = abs.groupValues[1].toInt()
            val min = abs.groupValues[2].ifEmpty { "0" }.toInt()
            val mer = abs.groupValues[3]
            if (h > 23 || min > 59) return null
            val pmWord = mer.startsWith("p") || Regex("shaam|शाम|raat|रात|evening|night").containsMatchIn(t)
            val amWord = mer.startsWith("a") || Regex("subah|सुबह|morning").containsMatchIn(t)
            val afternoon = Regex("dopahar|दोपहर|afternoon").containsMatchIn(t)
            val explicit = pmWord || amWord || afternoon
            if (pmWord && h < 12) h += 12
            if (afternoon && h in 1..6) h += 12
            if (amWord && h == 12) h = 0
            var dayOffset = when {
                Regex("parso|परसों|day after").containsMatchIn(t) -> 2
                Regex("\\bkal\\b|कल|tomorrow").containsMatchIn(t) -> 1
                else -> 0
            }
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            if (cal.timeInMillis <= now) {
                if (dayOffset == 0 && !explicit && h < 12 && cal.timeInMillis + 12 * 3600_000L > now) cal.add(Calendar.HOUR_OF_DAY, 12)
                else { cal.add(Calendar.DAY_OF_YEAR, 1); dayOffset++ }
            }
            whenMs = cal.timeInMillis
            t = t.removeRange(abs.range)
        } else return null

        val msg = t.split(Regex("\\s+")).filter { it.isNotBlank() && it !in fillers && !it.matches(Regex("\\d+")) }
            .joinToString(" ").trim().ifEmpty { "Reminder" }
        return ParsedReminder(whenMs, msg, repeat)
    }

    /** Used by the Tools screen: parse text like "10 minute baad chai" and schedule it. */
    fun addFromText(ctx: Context, text: String): String {
        val p = parse(text) ?: return "Samay samajh nahi aaya. Jaise likho: '10 minute baad chai pina' ya 'kal subah 7 baje gym'."
        val r = add(ctx, p.message, p.whenMs, p.repeat)
        return "Reminder set: ${describe(r)}"
    }
}
