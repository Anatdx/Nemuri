/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - App-level settings persisted in SharedPreferences.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri.data.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var verboseLogging: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE_LOGGING, true)
        set(value) = prefs.edit().putBoolean(KEY_VERBOSE_LOGGING, value).apply()

    // Master switch for the auto-freeze engine (mirror of policy.json; default off for safety).
    var autoFreezeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREEZE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREEZE, value).apply()

    var freezeDelaySeconds: Int
        get() = prefs.getInt(KEY_FREEZE_DELAY_SEC, DEFAULT_DELAY_SEC)
        set(value) = prefs.edit().putInt(KEY_FREEZE_DELAY_SEC, value).apply()

    private companion object {
        const val PREFS_NAME = "nemuri_settings"
        const val KEY_VERBOSE_LOGGING = "verbose_logging"
        const val KEY_AUTO_FREEZE = "auto_freeze_enabled"
        const val KEY_FREEZE_DELAY_SEC = "freeze_delay_sec"
        const val DEFAULT_DELAY_SEC = 5
    }
}
