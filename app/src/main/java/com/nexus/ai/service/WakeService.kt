package com.nexus.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.AvatarState
import com.nexus.ai.ui.MainActivity
import com.nexus.ai.ui.Perm

/**
 * EXPERIMENTAL "Hey Nexus" wake word. Keeps Android's speech recogniser listening while a visible notification is shown.
 * It uses battery, may beep on some phones, and Android/Infinix may stop it. Audio is only processed by the system recogniser;
 * NEXUS acts only on text that contains the wake word.
 */
class WakeService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "nexus_wake"
        private const val ID = 4201
        private const val STOP = "com.nexus.ai.STOP_WAKE"
        private val wakeRe = Regex("(hey|hi|ok|okay|हे|हेलो|हैलो)?\\s*(nexus|nexas|nexes|next us|nex us|नेक्सस|नेक्सेस|नैक्सस|नेक्स)", RegexOption.IGNORE_CASE)

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastStart = 0L
    private var fails = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Assistant.prefs.wakeWord = false
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle("NEXUS is listening for “Hey Nexus”")
            .setContentText("Tap Stop to turn the microphone listening off.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, n)
        running = true
        main.removeCallbacksAndMessages(null)
        main.post(loop)
        main.postDelayed(watchdog, 6000)
        return START_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (!Perm.hasMic(this@WakeService)) { stopSelf(); return }
            if (Assistant.state.value != AvatarState.IDLE) { main.postDelayed(this, 1200); return }
            lastStart = System.currentTimeMillis()
            Assistant.voice.startListening(
                partialCb = {},
                finalCb = { text -> onHeard(text) },
                errorCb = { fails++; main.postDelayed(this, if (fails > 5) 5000 else 600) },
                levelCb = {}
            )
        }
    }

    /** If the recogniser got cancelled by another part of the app, start again. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (Assistant.state.value == AvatarState.IDLE && System.currentTimeMillis() - lastStart > 15000) main.post(loop)
            main.postDelayed(this, 6000)
        }
    }

    private fun onHeard(text: String) {
        val m = wakeRe.find(text)
        if (m == null || text.isBlank()) { main.postDelayed(loop, 300); return }
        fails = 0
        val rest = text.removeRange(m.range).trim(' ', ',', '.', '!')
        if (rest.length >= 3) Assistant.send(rest, viaVoice = true) else Assistant.listen()
        main.postDelayed(loop, 1500)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { Assistant.voice.stopListening() } catch (_: Exception) {}
        super.onDestroy()
    }
}
