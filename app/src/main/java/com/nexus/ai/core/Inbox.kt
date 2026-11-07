package com.nexus.ai.core

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow

data class Incoming(
    val key: String,
    val app: String,
    val pkg: String,
    val sender: String,
    val text: String,
    val time: Long,
    val action: Notification.Action?,
    val read: Boolean = false
)

/** Messages seen through Notification Access. Kept in memory only (cleared when the app process ends). */
object Inbox {
    val items = MutableStateFlow<List<Incoming>>(emptyList())

    fun add(i: Incoming) {
        val cur = items.value.filterNot { it.sender == i.sender && it.text == i.text }
        items.value = (cur + i).takeLast(60)
    }

    fun unread(): List<Incoming> = items.value.filter { !it.read }
    fun last(): Incoming? = items.value.lastOrNull()
    fun markAllRead() { items.value = items.value.map { it.copy(read = true) } }
    fun clear() { items.value = emptyList() }

    /** Sends [text] through the notification's own reply action (the official reply mechanism). */
    fun reply(ctx: Context, item: Incoming, text: String): Boolean {
        val action = item.action ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (r in inputs) bundle.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
