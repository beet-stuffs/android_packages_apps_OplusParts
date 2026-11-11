/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Stops keyguard from listening for fingerprints while the device is in a pocket.
 *
 * Under-display sensors capture on their own once keyguard starts authenticating (ultrasonic
 * ones, and optical ones woken up by the touch panel), so blocking touches doesn't stop them.
 * The only thing outside SystemUI that keyguard follows is the user's "use fingerprint to
 * unlock" setting, so it is turned off while in the pocket and the user's values are put back
 * afterwards. They are saved to device protected storage first, so they are also put back
 * after a crash or reboot.
 */
internal class PocketFingerprintBlocker(context: Context) {

    private val context = context.applicationContext ?: context
    private val prefs = this.context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isBlocked: Boolean
        get() = prefs.contains(KEY_USER)

    fun setBlocked(blocked: Boolean) {
        if (blocked == isBlocked) return
        if (blocked) block() else restore()
    }

    private fun block() {
        val user = ActivityManager.getCurrentUser()
        val resolver = context.contentResolver
        val editor = prefs.edit().putInt(KEY_USER, user)
        SETTINGS.forEach { key ->
            editor.putString(key, Settings.Secure.getStringForUser(resolver, key, user) ?: UNSET)
        }
        if (!editor.commit()) {
            Log.e(TAG, "failed to save the fingerprint settings, not blocking")
            return
        }
        Log.i(TAG, "in the pocket, disabling fingerprint unlock")
        SETTINGS.forEach { Settings.Secure.putStringForUser(resolver, it, "0", user) }
    }

    fun restore() {
        if (!isBlocked) return
        val user = prefs.getInt(KEY_USER, ActivityManager.getCurrentUser())
        Log.i(TAG, "out of the pocket, restoring fingerprint unlock")
        SETTINGS.forEach { key ->
            val value = prefs.getString(key, UNSET).takeIf { it != UNSET }
            Settings.Secure.putStringForUser(context.contentResolver, key, value, user)
        }
        prefs.edit().clear().commit()
    }

    companion object {
        private const val TAG = "PocketMode"
        private const val PREFS_NAME = "pocket_fingerprint"
        private const val KEY_USER = "user"
        private const val UNSET = "\u0000unset"

        // BiometricService observes one or the other depending on the build. An unset
        // FINGERPRINT_KEYGUARD_ENABLED falls back to BIOMETRIC_KEYGUARD_ENABLED, so that one
        // is restored first.
        private val SETTINGS = listOf(
            Settings.Secure.BIOMETRIC_KEYGUARD_ENABLED,
            Settings.Secure.FINGERPRINT_KEYGUARD_ENABLED,
        )

        // Put the user's settings back if we were blocking when the device went down.
        fun restoreIfNeeded(context: Context) = PocketFingerprintBlocker(context).restore()
    }
}
