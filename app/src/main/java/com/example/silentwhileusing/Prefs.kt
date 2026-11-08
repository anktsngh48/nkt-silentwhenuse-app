package com.example.silentwhileusing

import android.content.Context
import android.content.SharedPreferences

/** Single source of truth shared by the app switch, the QS tile and the service. */
object Prefs {
    private const val FILE = "silent_prefs"
    const val KEY_ENABLED = "enabled"
    private const val KEY_SILENCED_BY_US = "silenced_by_us"
    private const val KEY_SAVED_RINGER_MODE = "saved_ringer_mode"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = get(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) =
        get(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Remember the user's ringer mode before we silence it (persisted so it survives restarts). */
    fun saveRingerMode(ctx: Context, mode: Int) =
        get(ctx).edit()
            .putBoolean(KEY_SILENCED_BY_US, true)
            .putInt(KEY_SAVED_RINGER_MODE, mode)
            .apply()

    /** The mode to restore, or null if we didn't silence the phone. */
    fun savedRingerMode(ctx: Context): Int? {
        val p = get(ctx)
        return if (p.getBoolean(KEY_SILENCED_BY_US, false)) p.getInt(KEY_SAVED_RINGER_MODE, 2) else null
    }

    fun isSilencedByUs(ctx: Context) = get(ctx).getBoolean(KEY_SILENCED_BY_US, false)

    fun clearSilenced(ctx: Context) =
        get(ctx).edit().remove(KEY_SILENCED_BY_US).remove(KEY_SAVED_RINGER_MODE).apply()
}
