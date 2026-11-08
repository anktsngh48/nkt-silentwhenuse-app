package com.example.silentwhileusing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after a reboot or app update if the feature was left on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Prefs.isEnabled(context)) SilenceController.setEnabled(context, true)
        }
    }
}
