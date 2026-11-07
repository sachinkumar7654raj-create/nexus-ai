package com.nexus.ai.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

/**
 * Real phone actions that need no risky permission. Returns a reply to speak, or null when the text is not a phone command
 * (then it goes to Gemini). Anything Android does not allow is explained honestly instead of faked.
 */
class PhoneCommands(private val ctx: Context) {

    private fun launch(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    fun tryHandle(raw: String): String? {
        val t = raw.lowercase(Locale.ROOT).trim()
        if (t.isEmpty()) return null
        return flashlight(t) ?: battery(t) ?: storage(t) ?: volume(t) ?: timer(t) ?: alarm(t)
            ?: settingsScreens(t) ?: call(t) ?: openApp(t)
    }

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    // ---------- Flashlight ----------
    private fun flashlight(t: String): String? {
        if (!has(t, "torch", "flashlight", "flash light", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ़्लैशलाइट")) return null
        val off = has(t, " off", "band", "बंद", "bund", "बन्द", "disable")
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "Is phone mein flashlight hardware nahi mila."
            cm.setTorchMode(id, !off)
            if (off) "Flashlight band kar di." else "Flashlight on kar di."
        } catch (e: Exception) {
            "Flashlight control nahi ho paya: ${e.message ?: "unknown error"}"
        }
    }

    // ---------- Battery ----------
    private fun battery(t: String): String? {
        if (!has(t, "battery", "बैटरी", "charge kitna", "charging")) return null
        if (has(t, "setting", "सेटिंग")) {
            return if (launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) "Battery settings khol di." else "Battery settings nahi khul payi."
        }
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Battery ki jankari nahi mil payi."
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return "Battery ki jankari nahi mil payi."
        return "Battery $pct percent hai${if (charging) ", aur charging ho rahi hai." else "."}"
    }

    // ---------- Storage ----------
    private fun storage(t: String): String? {
        if (!has(t, "storage", "स्टोरेज", "kitni jagah", "free space")) return null
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1_073_741_824.0
        val total = st.totalBytes / 1_073_741_824.0
        return String.format(Locale.US, "Aapke phone mein %.1f GB free hai, total %.1f GB mein se.", free, total)
    }

    // ---------- Volume ----------
    private fun volume(t: String): String? {
        val adjust = has(t, "badha", "बढ़ा", "बढा", "kam kar", "कम कर", "ghata", "घटा", "increase", "decrease")
        if (!(has(t, "volume", "वॉल्यूम") || (has(t, "awaaz", "आवाज") && adjust))) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val flags = AudioManager.FLAG_SHOW_UI
        return when {
            has(t, "mute", "zero", "बंद", "band") -> { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, flags); "Media volume mute kar diya." }
            has(t, "max", "full", "poori", "पूरी") -> { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, flags); "Media volume full kar diya." }
            has(t, "up", "badha", "बढ़ा", "बढा", "increase", "zyada", "ज्यादा", "jyada", "tez", "तेज") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags); "Volume badha diya."
            }
            has(t, "down", "kam", "कम", "ghata", "घटा", "decrease", "lower", "dheere", "धीरे") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags); "Volume kam kar diya."
            }
            else -> {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                "Media volume abhi $cur / $max hai. 'volume badhao' ya 'volume kam karo' bolo."
            }
        }
    }

    // ---------- Timer ----------
    private val timerRe = Regex("(\\d+)\\s*(second|sec|सेकंड|minute|min|मिनट|mint|hour|ghante|ghanta|घंटे|घंटा)")
    private fun timer(t: String): String? {
        if (!has(t, "timer", "टाइमर", "countdown")) return null
        val m = timerRe.find(t) ?: return "Kitne minute ka timer lagana hai? Jaise 'das minute ka timer lagao' bolo."
        val n = m.groupValues[1].toInt()
        val unit = m.groupValues[2]
        val secs = when {
            unit.startsWith("sec") || unit.startsWith("से") -> n
            unit.startsWith("h") || unit.startsWith("घं") || unit.startsWith("g") -> n * 3600
            else -> n * 60
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) "Timer $n ${m.groupValues[2]} ke liye clock app mein set kar diya." else "Clock app timer set nahi kar payi."
    }

    // ---------- Alarm ----------
    private val timeRe = Regex("(\\d{1,2})(?::|\\.)?(\\d{2})?\\s*(am|pm|baje|बजे|a\\.m|p\\.m)?")
    private fun alarm(t: String): String? {
        if (!has(t, "alarm", "अलार्म")) return null
        if (has(t, "show", "dikha", "दिखा", "list", "kholo", "open")) {
            return if (launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))) "Alarm list khol di." else "Alarm app nahi khul payi."
        }
        val m = timeRe.findAll(t).firstOrNull { it.groupValues[1].toIntOrNull() in 0..23 && (it.groupValues[3].isNotEmpty() || it.groupValues[2].isNotEmpty() || has(t, "baje", "बजे", "am", "pm")) }
            ?: return "Kitne baje ka alarm lagana hai? Jaise 'subah 6 baje ka alarm lagao' bolo."
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val mer = m.groupValues[3]
        val pmWord = mer.startsWith("p") || has(t, "shaam", "शाम", "raat", "रात", "evening", "night", "dopahar", "दोपहर", "afternoon")
        val amWord = mer.startsWith("a") || has(t, "subah", "सुबह", "morning")
        if (pmWord && h < 12) h += 12
        if (amWord && h == 12) h = 0
        if (min > 59) return "Time samajh nahi aaya, dobara bolo."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "NEXUS alarm").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) String.format(Locale.US, "Alarm %02d:%02d ke liye clock app ko bhej diya. Clock app mein ek baar check kar lena.", h, min)
        else "Clock app alarm set nahi kar payi."
    }

    // ---------- Settings screens ----------
    private fun settingsScreens(t: String): String? {
        val wantsSettings = has(t, "setting", "सेटिंग", "kholo", "खोलो", "open", "on karo", "off karo", "चालू", "बंद", " on", " off", "connect")
        if (!wantsSettings) return null
        val (action, name, note) = when {
            has(t, "wifi", "wi-fi", "वाईफाई", "वाई-फाई", "वाइफाई") -> Triple(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi", true)
            has(t, "bluetooth", "ब्लूटूथ") -> Triple(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", true)
            has(t, "location", "gps", "लोकेशन") -> Triple(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location", true)
            has(t, "display", "brightness", "डिस्प्ले", "ब्राइटनेस") -> Triple(Settings.ACTION_DISPLAY_SETTINGS, "Display", false)
            has(t, "airplane", "flight mode", "एयरप्लेन") -> Triple(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode", true)
            has(t, "hotspot", "हॉटस्पॉट") -> Triple(Settings.ACTION_WIRELESS_SETTINGS, "Network", true)
            has(t, "phone ki setting", "phone settings", "settings kholo", "सेटिंग खोलो", "open settings", "settings open") -> Triple(Settings.ACTION_SETTINGS, "Settings", false)
            else -> return null
        }
        val ok = if (action == Settings.ACTION_WIFI_SETTINGS && Build.VERSION.SDK_INT >= 29) launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) || launch(Intent(action)) else launch(Intent(action))
        if (!ok) return "$name settings nahi khul payi."
        return if (note) "$name ko Android 12 mein app se seedha on/off nahi kar sakte, isliye $name settings khol di hain. Wahan se aap switch kar sakte hain."
        else "$name settings khol di."
    }

    // ---------- Calling ----------
    private val numberRe = Regex("(\\+?\\d[\\d\\s-]{4,14}\\d)")
    private fun call(t: String): String? {
        val callWord = has(t, "call", "कॉल", "phone lagao", "फोन लगा", "dial", "dialer", "डायलर")
        if (!callWord) return null
        val imperative = has(t, "karo", "kar do", "lagao", "लगाओ", "करो", "कर दो", "dial", "dialer", "डायलर", "kholo") || t.startsWith("call")
        if (!imperative) return null
        val num = numberRe.find(t)?.value?.replace(Regex("[\\s-]"), "")
        if (num != null) {
            return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))) "Dialer mein $num daal diya hai. Call karne ke liye aap green button dabaiye."
            else "Dialer nahi khul paya."
        }
        if (has(t, "dialer", "डायलर", "dial")) {
            return if (launch(Intent(Intent.ACTION_DIAL))) "Dialer khol diya." else "Dialer nahi khul paya."
        }
        return null
    }

    // ---------- Open apps ----------
    private val aliases = mapOf(
        "youtube" to listOf("youtube", "यूट्यूब", "यू ट्यूब", "you tube"),
        "whatsapp" to listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप", "whats app", "व्हाट्स ऐप"),
        "instagram" to listOf("instagram", "इंस्टाग्राम", "insta"),
        "chrome" to listOf("chrome", "क्रोम", "browser", "ब्राउज़र"),
        "camera" to listOf("camera", "कैमरा"),
        "gallery" to listOf("gallery", "गैलरी", "photos", "फोटो"),
        "settings" to listOf("settings", "सेटिंग"),
        "maps" to listOf("maps", "मैप्स", "google maps"),
        "calculator" to listOf("calculator", "कैलकुलेटर"),
        "clock" to listOf("clock", "घड़ी"),
        "contacts" to listOf("contacts", "कॉन्टैक्ट्स"),
        "messages" to listOf("messages", "मैसेज", "sms")
    )
    private val openVerbs = listOf("open", "launch", "start", "kholo", "khol do", "khol", "खोलो", "खोल दो", "खोल", "ओपन", "chalu", "चालू", "chalao", "चलाओ", "चला दो")
    private val fillers = listOf("hey nexus", "nexus", "नेक्सस", "please", "plz", "ko", "ka", "app", "karo", "kar do", "kardo", "करो", "कर दो", "ऐप", "एप", "zara", "जरा", "mujhe", "मुझे", "the", "meri", "mera")

    private fun openApp(t: String): String? {
        if (openVerbs.none { t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") || t == it }) return null
        var name = t
        for (v in openVerbs.sortedByDescending { it.length }) name = name.replace(v, " ")
        for (f in fillers.sortedByDescending { it.length }) name = name.replace(Regex("(^|\\s)${Regex.escape(f)}(\\s|$)"), " ")
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.length < 3) return null
        val key = aliases.entries.firstOrNull { e -> e.value.any { name.contains(it) } }?.key ?: name

        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(key, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(key) }
            ?: if (key == "gallery") apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains("photos") } else null
        if (match != null) {
            val li = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            val label = match.loadLabel(pm)
            return if (li != null && launch(li)) "$label khol diya." else "$label ko khol nahi paya."
        }
        val looksLikeCommand = name.split(" ").size <= 2 && (t.startsWith("open ") || t.startsWith("launch ") ||
            has(t, " kholo", " खोलो", " khol do", " खोल दो", " open karo"))
        return if (looksLikeCommand) "'$name' naam ka app is phone mein nahi mila." else null
    }
}
