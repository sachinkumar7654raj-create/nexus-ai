package com.nexus.ai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Perm {
    fun has(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    fun hasMic(ctx: Context) = has(ctx, Manifest.permission.RECORD_AUDIO)

    fun listenerEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.contains(ctx.packageName, true) && it.contains("NexusAccessibilityService") }
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Plain-language reason shown before the system permission dialog. */
    fun explain(perm: String): String = when (perm) {
        Manifest.permission.RECORD_AUDIO -> "Microphone: aapki awaaz sunne ke liye. Sirf tab use hota hai jab aap mic dabate ho (ya wake word ON karo)."
        Manifest.permission.READ_CONTACTS -> "Contacts: 'Rahul ko call karo' ya 'WhatsApp par Rahul ko bolo' jaise commands mein naam se number dhundhne ke liye. Contacts phone se bahar nahi jaate."
        Manifest.permission.CALL_PHONE -> "Phone call: aapke 'haan' confirm karne ke baad seedha call lagane ke liye. Ye na do to NEXUS sirf dialer kholega."
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications: reminders ka alert dikhane ke liye."
        else -> "NEXUS ko ye permission is feature ke liye chahiye."
    }
}
