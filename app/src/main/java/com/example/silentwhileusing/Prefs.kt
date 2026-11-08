package com.example.silentwhileusing

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import java.text.DateFormat
import java.util.Date

/** Single source of truth shared by the app switch, the QS tile and the service. */
object Prefs {
    private const val FILE = "silent_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_LAST_EVENT = "last_event"
    private const val KEY_SILENCED_BY_US = "silenced_by_us"
    private const val KEY_SAVED_RINGER_MODE = "saved_ringer_mode"
    private const val KEY_SAVED_FILTER = "saved_interruption_filter"

    data class SavedSound(val ringerMode: Int, val interruptionFilter: Int)

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = get(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) =
        get(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Remember the user's sound settings before we silence the phone (survives restarts). */
    fun saveSound(ctx: Context, ringerMode: Int, interruptionFilter: Int) =
        get(ctx).edit()
            .putBoolean(KEY_SILENCED_BY_US, true)
            .putInt(KEY_SAVED_RINGER_MODE, ringerMode)
            .putInt(KEY_SAVED_FILTER, interruptionFilter)
            .commit()

    /** What to restore, or null if we didn't silence the phone. */
    fun savedSound(ctx: Context): SavedSound? {
        val p = get(ctx)
        if (!p.getBoolean(KEY_SILENCED_BY_US, false)) return null
        return SavedSound(
            p.getInt(KEY_SAVED_RINGER_MODE, 2),
            p.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        )
    }

    fun isSilencedByUs(ctx: Context) = get(ctx).getBoolean(KEY_SILENCED_BY_US, false)

    fun clearSilenced(ctx: Context) =
        get(ctx).edit()
            .remove(KEY_SILENCED_BY_US).remove(KEY_SAVED_RINGER_MODE).remove(KEY_SAVED_FILTER)
            .commit()

    /** Small "what happened last" line shown in the app, so problems can be spotted without a PC. */
    fun log(ctx: Context, message: String) {
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        get(ctx).edit().putString(KEY_LAST_EVENT, "$message at $time").apply()
    }

    fun lastEvent(ctx: Context): String? = get(ctx).getString(KEY_LAST_EVENT, null)
}
