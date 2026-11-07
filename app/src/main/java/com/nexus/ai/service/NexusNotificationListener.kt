package com.nexus.ai.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nexus.ai.core.Inbox
import com.nexus.ai.core.Incoming

/** Reads message notifications (only after the user enables Notification Access) so NEXUS can read and reply to them. */
class NexusNotificationListener : NotificationListenerService() {

    companion object {
        val WATCHED = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "com.samsung.android.messaging" to "Messages"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = WATCHED[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras ?: return
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        try {
            @Suppress("DEPRECATION")
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null && msgs.isNotEmpty()) {
                (msgs.last() as? Bundle)?.getCharSequence("text")?.toString()?.let { if (it.isNotBlank()) text = it }
            }
        } catch (_: Exception) {}
        if (text.isBlank() || title.isBlank()) return
        if (title.equals(app, true) && Regex("new message|checking for|messages from", RegexOption.IGNORE_CASE).containsMatchIn(text)) return
        title = title.replace(Regex("\\s*\\(\\d+ messages?\\)$"), "")
        Inbox.add(Incoming(sbn.key, app, sbn.packageName, title, text, sbn.postTime, replyAction(n)))
    }

    private fun replyAction(n: Notification): Notification.Action? {
        val all = ArrayList<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try { all.addAll(Notification.WearableExtender(n).actions) } catch (_: Exception) {}
        val withInput = all.filter { it.remoteInputs?.isNotEmpty() == true }
        return withInput.firstOrNull { it.title?.toString()?.contains("reply", true) == true } ?: withInput.firstOrNull()
    }
}
