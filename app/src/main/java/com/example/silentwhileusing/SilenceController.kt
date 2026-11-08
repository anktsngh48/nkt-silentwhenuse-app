package com.example.silentwhileusing

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat

/** Turns the feature on/off. Both the in-app switch and the QS tile go through here. */
object SilenceController {

    fun hasPolicyAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun openPolicyAccessSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Returns the state actually applied (false if the service could not be started). */
    fun setEnabled(ctx: Context, enabled: Boolean): Boolean {
        val intent = Intent(ctx, SilentService::class.java)
        var applied = enabled
        if (enabled) {
            Prefs.setEnabled(ctx, true)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background
                Log.w("SilenceController", "Could not start service", e)
                Prefs.setEnabled(ctx, false)
                applied = false
            }
        } else {
            Prefs.setEnabled(ctx, false)
            ctx.stopService(intent)
        }
        // Ask the system to refresh the Quick Settings tile
        TileService.requestListeningState(ctx, ComponentName(ctx, SilenceTileService::class.java))
        return applied
    }
}
