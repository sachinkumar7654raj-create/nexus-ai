package com.nexus.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexus.ai.core.Reminders

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id >= 0) Reminders.fire(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(context)
    }
}
