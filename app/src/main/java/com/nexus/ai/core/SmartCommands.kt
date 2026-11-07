package com.nexus.ai.core

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nexus.ai.service.NexusAccessibilityService
import com.nexus.ai.ui.StudioActivity
import java.util.Locale

/** What a command produced: a ready reply, or a question to send to the AI (optionally with extra context such as screen text). */
data class CmdResult(val reply: String? = null, val aiPrompt: String? = null, val extra: String? = null)

/**
 * Commands that need contacts, messaging, reminders, notes, memory or accessibility.
 * Anything that sends or calls goes through [ask] so the user must confirm first.
 */
class SmartCommands(
    private val ctx: Context,
    private val ask: (String, () -> String) -> Unit,
    private val needPermission: (String) -> Unit
) {
    private fun r(s: String) = CmdResult(reply = s)
    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    fun handle(raw: String): CmdResult? {
        val t = raw.lowercase(Locale.ROOT).trim()
        if (t.isEmpty()) return null
        return reminders(t) ?: notes(raw, t) ?: memory(raw, t) ?: studio(raw, t) ?: inbox(raw, t)
            ?: whatsappSend(t) ?: videoCall(t) ?: callContact(t) ?: sms(t) ?: screen(raw, t) ?: access(t)
    }

    private fun launch(i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true } catch (e: Exception) { false }

    // ============================================================ reminders
    private fun reminders(t: String): CmdResult? {
        val cue = has(t, "yaad dila", "yaad dilana", "remind", "reminder", "याद दिला", "रिमाइंडर")
        if (!cue) return null
        if (has(t, "list", "dikha", "batao", "kaunse", "kitne", "show", "padho", "sunao", "दिखा", "बता", "what are") && !Regex("\\d").containsMatchIn(t)) {
            val list = Reminders.all(ctx)
            return r(if (list.isEmpty()) "Abhi koi reminder set nahi hai." else "Aapke reminders: " + list.take(6).joinToString(". ") { Reminders.describe(it) })
        }
        if (has(t, "cancel", "delete", "hata", "remove", "clear", "हटा", "डिलीट", "रद्द")) {
            ask("Saare reminders delete kar dun?") { Reminders.clear(ctx); "Saare reminders hata diye." }
            return r("Saare reminders delete kar dun? Haan ya nahi bolo.")
        }
        val p = Reminders.parse(t) ?: return r("Kab yaad dilaun? Jaise bolo: '10 minute baad chai pina yaad dilao' ya 'kal subah 7 baje gym yaad dilao'.")
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        val rem = Reminders.add(ctx, p.message, p.whenMs, p.repeat)
        val late = if (!Reminders.canUseExact(ctx)) " (Exact alarm permission nahi hai, isliye thoda late ho sakta hai.)" else ""
        return r("Theek hai, reminder set: ${Reminders.describe(rem)}.$late")
    }

    // ============================================================ notes
    private fun notes(raw: String, t: String): CmdResult? {
        if (!(Regex("\\bnotes?\\b").containsMatchIn(t) || t.contains("नोट"))) return null
        val store = Assistant.notes
        if (has(t, "delete", "hata", "clear", "saaf", "मिटा", "हटा")) {
            ask("Saare notes delete kar dun?") { store.clear(); "Saare notes delete kar diye." }
            return r("Saare notes delete kar dun? Haan ya nahi bolo.")
        }
        if (has(t, "dikha", "padh", "sun", "list", "batao", "show", "read", "kya kya", "दिखा", "पढ़", "सुना")) {
            val all = store.all()
            return r(if (all.isEmpty()) "Abhi koi note nahi hai." else "Aapke notes: " + all.takeLast(6).joinToString(". ") { it.text })
        }
        val after = Regex("(?:likho|likh do|likh lo|likh|add karo|add|kar lo|save karo|save|take|bana|:)\\s*[:,-]?\\s*(?:ki\\s+)?(.*)$", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        var content = after
        if (content.isBlank()) content = raw.substringBefore("note", raw.substringBefore("नोट")).trim()
        content = content.trim(' ', ':', '-', ',')
        if (content.length < 2) return r("Kya note karna hai? Jaise bolo: 'note likho kal doodh lena hai'.")
        store.add(content)
        return r("Note kar liya: $content")
    }

    // ============================================================ memory
    private val secretWords = Regex("password|passcode|otp|cvv|pin code|card number|atm|पासवर्ड|ओटीपी", RegexOption.IGNORE_CASE)

    private fun memory(raw: String, t: String): CmdResult? {
        val nameM = Regex("(?:mera naam|my name is|मेरा नाम)\\s+(\\S+)", RegexOption.IGNORE_CASE).find(raw)
        if (nameM != null && !has(t, "kya", "?")) {
            val name = nameM.groupValues[1].trim(' ', '.', ',')
            Assistant.prefs.userName = name
            if (Assistant.prefs.memoryEnabled) Assistant.memory.add("User's name is $name")
            return r("Achha, ab se main aapko $name bulaungi.")
        }
        if (has(t, "memory clear", "sab bhool", "memory delete", "yaadasht clear", "forget everything", "सब भूल")) {
            ask("Meri saari yaaddasht (memory) delete kar dun?") { Assistant.memory.clear(); "Memory saaf kar di." }
            return r("Saari memory delete kar dun? Haan ya nahi bolo.")
        }
        if (has(t, "kya yaad hai", "memory dikha", "what do you remember", "meri memory", "kya kya yaad")) {
            val all = Assistant.memory.all()
            return r(if (all.isEmpty()) "Abhi mujhe aapke baare mein kuch yaad rakhne ko nahi diya gaya." else "Mujhe yaad hai: " + all.joinToString(". ") { it.text })
        }
        val m = Regex("(?:yaad rakho|yaad rakhna|yaad rakh|remember that|याद रखो|याद रखना)\\s*[:,-]?\\s*(?:ki\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (m != null && !t.contains("dila")) {
            val fact = m.groupValues[1].trim()
            if (secretWords.containsMatchIn(fact)) return r("Main password, OTP ya card jaisi private cheezein yaad nahi rakhti. Koi aur baat yaad rakhwani hai?")
            if (!Assistant.prefs.memoryEnabled) return r("Memory band hai. Settings mein ON karo to main yaad rakhungi.")
            Assistant.memory.add(fact)
            return r("Theek hai, yaad rakh liya: $fact")
        }
        return null
    }

    // ============================================================ studio
    private fun studio(raw: String, t: String): CmdResult? {
        val site = has(t, "website", "webpage", "web page", "landing page", "portfolio", "web app", "वेबसाइट")
        val build = has(t, "banao", "bana do", "bana de", "create", "build", "generate", "make", "बनाओ", "बना दो")
        if (!site || !build) return null
        val ok = launch(Intent(ctx, StudioActivity::class.java).putExtra("prompt", raw))
        return r(if (ok) "Studio khol rahi hoon, website bana rahi hoon." else "Studio nahi khul paya.")
    }

    // ============================================================ inbox (WhatsApp & others)
    private fun listenerEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun needListener(): CmdResult {
        launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        return r("Messages padhne ke liye Notification Access chahiye. Maine settings khol di hain, wahan NEXUS AI ko ON kar do, phir dobara bolo.")
    }

    private fun inbox(raw: String, t: String): CmdResult? {
        val appWord = has(t, "whatsapp", "व्हाट्सएप", "वाट्सएप", "व्हाट्सऐप", "telegram", "insta", "sms")
        val phrase = has(t, "mere message", "naye message", "naya message", "new messages", "unread message", "मेरे मैसेज", "नए मैसेज", "message check", "msg check", "मैसेज चेक")
        val wa = appWord || phrase
        val readCue = has(t, "check", "dekho", "dekh", "padho", "sunao", "aaya", "aaye", "naya", "naye", "kisne", "read", "new", "unread", "बताओ", "सुनाओ", "पढ़ो", "देखो")
        val sendCue = has(t, "bhej", "send", "likho", "bolo", "बोलो") && has(t, " ko ", "to ", "ko ")
        if (wa && readCue && !sendCue && !has(t, "kholo", "open")) {
            if (!listenerEnabled()) return needListener()
            val unread = Inbox.unread()
            if (unread.isEmpty()) return r("Koi naya message nahi hai. (Main sirf wahi messages dekh sakti hoon jo notification access ON hone ke baad aaye.)")
            val lines = unread.takeLast(5).map { "${it.sender}: ${it.text}" }
            val spoken = lines.joinToString(". ")
            Inbox.markAllRead()
            return if (has(t, "summar", "samjha", "explain", "matlab", "saar", "सारांश", "समझा"))
                CmdResult(aiPrompt = "In messages ka chhota summary do aur batao kya jawab dena chahiye:", extra = lines.joinToString("\n"))
            else r("Aapko ${unread.size} naye message hain. $spoken")
        }
        if (has(t, "reply suggest", "kya reply", "reply kya", "suggest reply", "jawab suggest", "kya jawab")) {
            val last = Inbox.last() ?: return r("Reply suggest karne ke liye koi recent message nahi hai.")
            return CmdResult(aiPrompt = "${last.sender} ne ${last.app} par ye message bheja hai. 3 chhote, natural reply options do (Hinglish mein):", extra = last.text)
        }
        val m = Regex("(?:usko|use|usse|unko|isko|inko|unhe|उसको|उसे|उन्हें)\\s+(?:bolo|bol do|keh do|bata do|batao|bhej do|बोलो|बोल दो|कह दो)\\s+(?:ki\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("(?:^|\\s)(?:reply|jawab|जवाब)\\s*(?:karo|do|kar do|bhejo)?\\s*[:,-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("^(?:reply (?:with|saying)|tell (?:him|her|them)(?: that)?)\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())
        if (m != null) {
            val last = Inbox.last() ?: return r("Reply karne ke liye koi recent message nahi hai.")
            val text = m.groupValues[1].trim()
            ask("${last.sender} ko ${last.app} par reply bhejun: “$text”?") {
                if (Inbox.reply(ctx, last, text)) "Reply bhej diya." else "Is message mein direct reply ka option nahi mila. ${last.app} khol kar bhejna padega."
            }
            return r("${last.sender} ko ${last.app} par reply bhejne ke liye ready hoon: “$text”. Bhej doon? Haan ya nahi bolo.")
        }
        return null
    }

    // ============================================================ contacts
    private fun contactsOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun findContacts(name: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 5) {
                    val n = c.getString(0).orEmpty(); val num = c.getString(1).orEmpty()
                    if (num.isNotBlank() && out.none { it.second.filter(Char::isDigit).takeLast(10) == num.filter(Char::isDigit).takeLast(10) }) out.add(n to num)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private class Found(val name: String, val num: String)
    private class Resolved(val contact: Found?, val error: CmdResult?)

    /** Finds the best matching contact, or explains why not. */
    private fun resolve(name: String): Resolved {
        if (!contactsOk()) {
            needPermission(Manifest.permission.READ_CONTACTS)
            return Resolved(null, r("Contact dhundhne ke liye Contacts permission chahiye. Allow karke dobara bolo."))
        }
        val found = findContacts(name)
        if (found.isEmpty()) return Resolved(null, r("'$name' naam ka contact nahi mila. (Contact agar Hindi mein save hai to naam Hindi mein bolo.)"))
        return Resolved(Found(found.first().first, found.first().second), null)
    }

    private fun intlNumber(num: String): String {
        val d = num.filter { it.isDigit() }
        return when {
            num.trim().startsWith("+") -> d
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }

    private val nameNoise = setOf(
        "hey", "nexus", "ko", "call", "karo", "kar", "do", "lagao", "laga", "phone", "please", "mujhe", "ek", "ki", "ka", "ke", "to", "make", "a",
        "dial", "ring", "whatsapp", "video", "on", "pe", "par", "me", "mein", "se", "bhej", "bhejo", "message", "msg", "sms", "send", "text",
        "कॉल", "करो", "को", "फोन", "लगाओ", "कर", "दो", "वीडियो", "व्हाट्सएप", "पर", "से", "मैसेज", "भेजो", "नेक्सस"
    )

    private fun nameFrom(t: String): String =
        t.split(Regex("\\s+")).filter { it.isNotBlank() && it !in nameNoise }.joinToString(" ").trim()

    private fun callContact(t: String): CmdResult? {
        if (!has(t, "call", "कॉल", "phone lagao", "फोन लगा", "ring")) return null
        if (has(t, "video", "वीडियो", "whatsapp")) return null
        if (Regex("\\d{5,}").containsMatchIn(t)) return null
        val imperative = has(t, "karo", "kar do", "lagao", "laga do", "करो", "कर दो", "लगाओ") || t.startsWith("call ") || t.startsWith("phone ")
        if (!imperative) return null
        val name = nameFrom(t)
        if (name.isEmpty() || name.split(" ").size > 3) return null
        val res = resolve(name)
        val contact = res.contact ?: return res.error
        val cname = contact.name
        val num = contact.num
        ask("$cname ko call lagaun?") {
            val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(num)}"))
            if (launch(i)) (if (canCall) "$cname ko call laga rahi hoon." else "Dialer mein $cname ka number daal diya, green button dabao.") else "Call nahi lag paya."
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) needPermission(Manifest.permission.CALL_PHONE)
        return r("$cname ($num) ko call lagaun? Haan ya nahi bolo.")
    }

    private fun sms(t: String): CmdResult? {
        if (has(t, "whatsapp", "व्हाट्सएप")) return null
        val m = Regex("^(?:hey nexus\\s+)?(.+?)\\s+(?:ko|को)\\s+(?:sms|message|msg|text|मैसेज)\\s+(?:bhejo|bhej do|send karo|karo|likho|भेजो|कर दो)\\s*(.*)$").find(t) ?: return null
        val name = nameFrom(m.groupValues[1])
        val body = m.groupValues[2].trim().removePrefix("ki ").trim()
        if (name.isEmpty()) return null
        if (body.isEmpty()) return r("$name ko kya message bhejna hai? Jaise: '$name ko sms bhejo main late hoon'.")
        val res = resolve(name)
        val contact = res.contact ?: return res.error
        val cname = contact.name
        val num = contact.num
        ask("$cname ko SMS likh kar ready karun: “$body”?") {
            val ok = launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(num)}")).putExtra("sms_body", body))
            if (ok) {
                NexusAccessibilityService.instance?.clickWithRetry(listOf("Send SMS", "Send", "भेजें"), 6, 800) {}
                if (NexusAccessibilityService.instance != null) "SMS ready kar diya aur Send dabane ki koshish kar rahi hoon." else "SMS app khol diya, message likha hua hai. Send aap dabana."
            } else "SMS app nahi khul payi."
        }
        return r("$name ko SMS bhejun: “$body”? Haan ya nahi bolo.")
    }

    // ============================================================ WhatsApp send / video call
    private fun openWhatsAppChat(num: String, text: String?): Boolean {
        val uri = Uri.parse("https://wa.me/${intlNumber(num)}" + if (text != null) "?text=${Uri.encode(text)}" else "")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            val i = Intent(Intent.ACTION_VIEW, uri)
            if (pkg != null) i.setPackage(pkg)
            if (launch(i)) return true
        }
        return false
    }

    private fun whatsappSend(t: String): CmdResult? {
        if (!has(t, "whatsapp", "व्हाट्सएप", "वाट्सएप")) return null
        if (has(t, "video")) return null
        if (!has(t, "bhej", "send", "likho", "bolo", "message", "msg", "भेज", "बोलो", "मैसेज")) return null
        var s = t.replace(Regex("\\b(whatsapp|pe|par|me|mein|on|via)\\b"), " ").replace("व्हाट्सएप", " ").replace("वाट्सएप", " ").replace(Regex("\\s+"), " ").trim()
        s = s.removePrefix("hey nexus").removePrefix("nexus").trim()
        // "<name> ko bolo <message>"  (verb first - the most natural Hinglish order)
        val verbFirst = Regex("^(?:message |msg )?(.+?)\\s+(?:ko|को)\\s+(?:message bhejo|message bhej do|msg bhejo|message likho|message karo|msg karo|karo ki|kar do ki|bolo|bol do|bhejo|bhej do|likho|keh do|बोलो|भेजो|लिखो)\\s+(?:ki\\s+)?(.+)$").find(s)
        if (verbFirst != null) return buildWaSend(nameFrom(verbFirst.groupValues[1]), verbFirst.groupValues[2].trim())
        // "send <message> to <name>"
        val english = Regex("^(?:send|text)\\s+(.+?)\\s+to\\s+(.+)$").find(s)
        if (english != null) return buildWaSend(nameFrom(english.groupValues[2]), english.groupValues[1].trim())
        // "<name> ko <message> bhejo"  (verb last)
        val verbLast = Regex("^(?:message |msg |text )?(.+?)\\s+(?:ko|को)\\s+(?:message |msg |text )?(.+?)\\s+(?:bhejo|bhej do|bhej de|send karo|send|likho|bolo|bol do|message karo|भेजो|भेज दो|बोलो)$").find(s)
            ?: return null
        return buildWaSend(nameFrom(verbLast.groupValues[1]), verbLast.groupValues[2].trim().removePrefix("ki ").trim())
    }

    private fun buildWaSend(name: String, body: String): CmdResult {
        if (name.isEmpty() || body.isEmpty()) return r("Kisko aur kya message bhejna hai? Jaise: 'WhatsApp par Rahul ko bolo main late hoon'.")
        val res = resolve(name)
        val contact = res.contact ?: return res.error
        val cname = contact.name
        val num = contact.num
        ask("$cname ko WhatsApp par bhejun: “$body”?") {
            if (!openWhatsAppChat(num, body)) "WhatsApp nahi khul paya."
            else {
                val acc = NexusAccessibilityService.instance
                acc?.clickWithRetry(listOf("Send", "भेजें"), 10, 700) {}
                if (acc != null) "$cname ki chat khol di aur Send dabane ki koshish kar rahi hoon." else "$cname ki chat khol di, message likha hua hai. Send aap dabana (Accessibility ON karoge to main khud bhej dungi)."
            }
        }
        return r("$cname ko WhatsApp par bhejun: “$body”? Haan ya nahi bolo.")
    }

    private fun videoCall(t: String): CmdResult? {
        if (!has(t, "video call", "video कॉल", "वीडियो कॉल", "video karo")) return null
        val name = nameFrom(t.replace("video call", " ").replace("वीडियो कॉल", " "))
        if (name.isEmpty()) return r("Kisko video call karna hai? Jaise: 'Rahul ko WhatsApp video call karo'.")
        val res = resolve(name)
        val contact = res.contact ?: return res.error
        val cname = contact.name
        val num = contact.num
        ask("$cname ko WhatsApp video call lagaun?") {
            if (!openWhatsAppChat(num, null)) "WhatsApp nahi khul paya."
            else {
                val acc = NexusAccessibilityService.instance
                acc?.clickWithRetry(listOf("Video call", "वीडियो कॉल"), 12, 700) {}
                if (acc != null) "$cname ki chat khol di, video call button dabane ki koshish kar rahi hoon." else "$cname ki chat khol di. Video call ka icon aap dabao (Accessibility ON karoge to main dabaungi)."
            }
        }
        return r("$cname ($num) ko WhatsApp video call lagaun? Haan ya nahi bolo.")
    }

    // ============================================================ screen understanding + accessibility actions
    private fun needAccessibility(): CmdResult {
        launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return r("Iske liye Accessibility permission chahiye. Maine settings khol di hain. Wahan 'NEXUS AI' ko chun kar ON kar do, phir dobara bolo. Main sirf tab kaam karti hoon jab aap kahte ho.")
    }

    private fun screen(raw: String, t: String): CmdResult? {
        if (!has(t, "screen par kya", "screen pe kya", "screen kya", "screen padho", "screen ko padho", "screen explain", "read screen", "screen read",
                "is screen", "स्क्रीन पर क्या", "स्क्रीन पे क्या", "स्क्रीन पढ़ो", "what's on screen", "what is on my screen", "screen samjha", "screen dekh", "screen ko dekh")) return null
        val svc = NexusAccessibilityService.instance ?: return needAccessibility()
        val text = svc.readScreen()
        if (text.isBlank()) return r("Screen par mujhe koi text nahi mila (shayad ye image ya secure screen hai).")
        return CmdResult(aiPrompt = "User ka sawaal: \"$raw\". Neeche unke phone ki current screen ka text hai. Simple Hinglish mein batao screen par kya hai aur zaroori ho to kya karna chahiye.", extra = text)
    }

    private fun access(t: String): CmdResult? {
        val back = t == "back" || has(t, "back jao", "piche jao", "pichhe jao", "go back", "वापस जाओ", "पीछे जाओ")
        val home = t == "home" || t.startsWith("home jao") || t.startsWith("go home") || t.startsWith("home screen par jao") || t.startsWith("home screen kholo") || has(t, "होम पर जाओ")
        val recents = has(t, "recent apps", "recents", "recent kholo", "रिसेंट")
        val notif = has(t, "notification panel", "notifications kholo", "notification kholo", "notification dikhao", "notification khol", "नोटिफिकेशन खोलो")
        val quick = has(t, "quick settings", "quick setting")
        val lock = has(t, "screen lock karo", "phone lock karo", "lock screen", "lock kar do", "phone lock kar")
        val shot = has(t, "screenshot lo", "take screenshot", "screenshot le", "screenshot kar", "स्क्रीनशॉट")
        val scrollDown = has(t, "scroll down", "neeche scroll", "scroll neeche", "page neeche", "नीचे स्क्रॉल")
        val scrollUp = has(t, "scroll up", "upar scroll", "scroll upar", "page upar", "ऊपर स्क्रॉल")
        val click = Regex("^(?:click|tap|press)\\s+(?:on\\s+)?(.+)$").find(t)?.groupValues?.get(1)
            ?: Regex("^(.+?)\\s+(?:par|pe)\\s+(?:click|tap|press|dabao|dabana)(?:\\s+karo|\\s+kar do)?$").find(t)?.groupValues?.get(1)
        val type = Regex("^type\\s+(.+)$").find(t)?.groupValues?.get(1)

        val any = back || home || recents || notif || quick || lock || shot || scrollDown || scrollUp || click != null || type != null
        if (!any) return null
        val svc = NexusAccessibilityService.instance ?: return needAccessibility()
        fun g(action: Int, ok: String) = r(if (svc.global(action)) ok else "Ye action nahi ho paya.")
        return when {
            back -> g(AccessibilityService.GLOBAL_ACTION_BACK, "Peeche aa gayi.")
            home -> g(AccessibilityService.GLOBAL_ACTION_HOME, "Home screen par aa gayi.")
            recents -> g(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps khol diye.")
            notif -> g(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notification panel khol diya.")
            quick -> g(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings khol di.")
            lock -> if (Build.VERSION.SDK_INT >= 28) g(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Screen lock kar di.") else r("Ye Android version screen lock ka support nahi karta.")
            shot -> if (Build.VERSION.SDK_INT >= 28) g(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot le liya.") else r("Ye Android version screenshot ka support nahi karta.")
            scrollDown -> r(if (svc.scroll(true)) "Neeche scroll kiya." else "Scroll nahi ho paya.")
            scrollUp -> r(if (svc.scroll(false)) "Upar scroll kiya." else "Scroll nahi ho paya.")
            click != null -> r(if (svc.clickText(listOf(click.trim()))) "'${click.trim()}' daba diya." else "Screen par '${click.trim()}' naam ka button nahi mila.")
            else -> r(if (svc.typeText(type ?: "")) "Likh diya." else "Koi text box select nahi hai. Pehle text box par tap karo.")
        }
    }
}
