package com.indodaxbot.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.indodaxbot.BotApp
import com.indodaxbot.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the bot alive in the background (foreground service with a persistent notification).
 * The engine itself lives in the app container; this service only keeps the process running and
 * shows the status.
 */
class BotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = (application as BotApp).container
        if (intent?.action == ACTION_STOP) {
            c.botStore.enabled = false
            c.engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !c.botStore.enabled) {   // system restart after the user had stopped the bot
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val first = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        acquireWakeLock()
        c.engine.start()
        statusJob?.cancel()
        statusJob = scope.launch {
            c.engine.status.collect { s ->
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(if (s.error != null) "${s.message} · ${s.error}" else s.message))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        (application as BotApp).container.engine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "indodaxbot:engine").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bot status", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Indodax Bot (PAPER)")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop
                ).build()
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "bot_status"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.indodaxbot.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BotService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BotService::class.java))
        }
    }
}
