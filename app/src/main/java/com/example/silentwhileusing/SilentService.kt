package com.example.silentwhileusing

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Phone unlocked / in use -> save current sound settings, switch to silent.
 * Phone put down (screen off) -> restore the saved sound settings.
 */
class SilentService : Service() {

    companion object {
        const val ACTION_DISABLE = "com.example.silentwhileusing.action.DISABLE"
        private const val CHANNEL_ID = "silent_service"
        private const val NOTIFICATION_ID = 1

        /** True while the service is alive in this process (false if the phone killed it). */
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private lateinit var notifications: NotificationManager

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) silence()
                Intent.ACTION_USER_PRESENT -> silence()
                Intent.ACTION_SCREEN_OFF -> restore()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audio = getSystemService(AudioManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // System broadcasts: register as exported so no OEM permission check can drop them
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        if (power.isInteractive && !keyguard.isKeyguardLocked) silence() else restore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            SilenceController.setEnabled(this, false) // stops this service
            return START_NOT_STICKY
        }
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        restore() // never leave the phone silenced after turning the feature off
        super.onDestroy()
    }

    private fun silence() {
        if (!SilenceController.hasPolicyAccess(this)) {
            Prefs.log(this, "Couldn't silence: Do Not Disturb access is off")
            return
        }
        try {
            // Save the user's settings only once per "use", then (re)apply silent every time.
            // Re-applying fixes phones where the ringer quietly changed back by itself.
            if (!Prefs.isSilencedByUs(this)) {
                Prefs.saveSound(this, audio.ringerMode, notifications.currentInterruptionFilter)
            }
            audio.ringerMode = AudioManager.RINGER_MODE_SILENT
            Prefs.log(this, "Silenced")
        } catch (e: SecurityException) {
            Prefs.log(this, "Android blocked silencing")
        }
    }

    private fun restore() {
        val saved = Prefs.savedSound(this) ?: return
        try {
            // Silent mode can switch Do Not Disturb on (e.g. Pixel, Samsung). Undo that FIRST,
            // otherwise Android ignores the request to turn the ringer back on.
            if (saved.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
                notifications.currentInterruptionFilter != saved.interruptionFilter
            ) {
                notifications.setInterruptionFilter(saved.interruptionFilter)
            }
            audio.ringerMode = saved.ringerMode
            Prefs.log(this, "Sound restored")
        } catch (e: SecurityException) {
            Prefs.log(this, "Android blocked restoring sound")
        }
        Prefs.clearSilenced(this)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val disable = PendingIntent.getService(
            this, 1, Intent(this, SilentService::class.java).setAction(ACTION_DISABLE), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.turn_off), disable)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
