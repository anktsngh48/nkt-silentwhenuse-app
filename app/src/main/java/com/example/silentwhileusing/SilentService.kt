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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Phone unlocked / in use -> save current sound settings, switch to silent.
 * Phone put down (screen off) -> restore the saved sound settings.
 *
 * The user is always the boss:
 *  - If Do Not Disturb is already on when they unlock, we don't touch anything.
 *  - If they change the ringer or Do Not Disturb themselves while we have the phone silenced,
 *    we stop managing it and leave their choice in place when the phone is locked.
 */
class SilentService : Service() {

    companion object {
        const val ACTION_DISABLE = "com.example.silentwhileusing.action.DISABLE"
        private const val CHANNEL_ID = "silent_service"
        private const val NOTIFICATION_ID = 1

        /** Sound changes within this time after our own change are caused by us, not the user. */
        private const val OWN_CHANGE_GRACE_MS = 3_000L

        /** True while the service is alive in this process (false if the phone killed it). */
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private lateinit var notifications: NotificationManager

    /** When we last changed the ringer or Do Not Disturb ourselves. */
    private var lastOwnChangeAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) silence()
                Intent.ACTION_USER_PRESENT -> silence()
                Intent.ACTION_SCREEN_OFF -> restore()
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onSoundSettingsChanged()
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
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
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

    private fun isDndOn(): Boolean {
        val f = notifications.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** The ringer or Do Not Disturb changed. If we didn't do it, the user (or a DND schedule) did. */
    private fun onSoundSettingsChanged() {
        if (!Prefs.isSilencedByUs(this) || Prefs.userTookOver(this)) return
        if (SystemClock.elapsedRealtime() - lastOwnChangeAt < OWN_CHANGE_GRACE_MS) return
        Prefs.setUserTookOver(this)
        Prefs.log(this, "You changed the sound yourself, so it was left as you set it")
    }

    private fun silence() {
        if (!SilenceController.hasPolicyAccess(this)) {
            Prefs.log(this, "Couldn't silence: Do Not Disturb access is off")
            return
        }
        if (Prefs.userTookOver(this)) return // the user is in charge until the phone is locked

        val alreadySilencedByUs = Prefs.isSilencedByUs(this)
        if (!alreadySilencedByUs && isDndOn()) {
            // Do Not Disturb was switched on by the user or a schedule: it's already quiet,
            // and touching anything could end up switching their Do Not Disturb off later.
            Prefs.log(this, "Do Not Disturb was already on, so nothing was changed")
            return
        }
        try {
            if (!alreadySilencedByUs) {
                Prefs.saveSound(this, audio.ringerMode, notifications.currentInterruptionFilter)
            }
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                lastOwnChangeAt = SystemClock.elapsedRealtime()
                audio.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            Prefs.log(this, "Silenced")
        } catch (e: SecurityException) {
            Prefs.log(this, "Android blocked silencing")
        }
    }

    private fun restore() {
        val saved = Prefs.savedSound(this) ?: return
        try {
            if (Prefs.userTookOver(this)) {
                restoreAfterUserChange(saved)
            } else {
                // Silent mode can switch Do Not Disturb on (e.g. Pixel, Samsung). Undo that FIRST,
                // otherwise Android ignores the request to turn the ringer back on.
                lastOwnChangeAt = SystemClock.elapsedRealtime()
                if (saved.interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
                    notifications.currentInterruptionFilter != saved.interruptionFilter
                ) {
                    notifications.setInterruptionFilter(saved.interruptionFilter)
                }
                audio.ringerMode = saved.ringerMode
                Prefs.log(this, "Sound restored")
            }
        } catch (e: SecurityException) {
            Prefs.log(this, "Android blocked restoring sound")
        }
        Prefs.clearSilenced(this)
    }

    /** The user changed things while silenced: keep their choice, only undo what is still ours. */
    private fun restoreAfterUserChange(saved: Prefs.SavedSound) {
        when {
            // They switched Do Not Disturb on: they want quiet. Don't touch anything.
            isDndOn() ->
                Prefs.log(this, "Do Not Disturb left on, as you set it")

            // DND is off and the ringer is still on the silent we set (e.g. they switched DND
            // on and off again): bring their normal sound back.
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT &&
                saved.ringerMode != AudioManager.RINGER_MODE_SILENT -> {
                lastOwnChangeAt = SystemClock.elapsedRealtime()
                audio.ringerMode = saved.ringerMode
                Prefs.log(this, "Sound restored")
            }

            // They picked ring / vibrate themselves: leave it.
            else -> Prefs.log(this, "Sound left as you set it")
        }
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
