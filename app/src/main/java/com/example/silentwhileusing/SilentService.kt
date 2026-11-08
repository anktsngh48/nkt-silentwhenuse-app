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
 * Screen on  (and phone unlocked) -> save current ringer mode, switch to silent.
 * Screen off                      -> restore the saved ringer mode.
 *
 * SCREEN_ON / SCREEN_OFF can only be received by a receiver registered at runtime,
 * so a foreground service keeps it alive.
 */
class SilentService : Service() {

    companion object {
        const val ACTION_DISABLE = "com.example.silentwhileusing.action.DISABLE"
        private const val CHANNEL_ID = "silent_service"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Screen woke up without a lock screen (e.g. no screen lock set) -> in use
                Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) silence()
                // User unlocked the phone -> in use
                Intent.ACTION_USER_PRESENT -> silence()
                Intent.ACTION_SCREEN_OFF -> restore()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)

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
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // The user is almost certainly looking at the phone right now (they just enabled it)
        if (power.isInteractive && !keyguard.isKeyguardLocked) silence()
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
        unregisterReceiver(screenReceiver)
        restore() // never leave the phone silenced after turning the feature off
        super.onDestroy()
    }

    private fun silence() {
        if (Prefs.isSilencedByUs(this)) return
        if (!SilenceController.hasPolicyAccess(this)) return
        val current = audio.ringerMode
        if (current == AudioManager.RINGER_MODE_SILENT) return // already silent, nothing to restore later
        try {
            Prefs.saveRingerMode(this, current)
            audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (e: SecurityException) {
            Prefs.clearSilenced(this)
        }
    }

    private fun restore() {
        val saved = Prefs.savedRingerMode(this) ?: return
        try {
            audio.ringerMode = saved
        } catch (_: SecurityException) {
        }
        Prefs.clearSilenced(this)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
