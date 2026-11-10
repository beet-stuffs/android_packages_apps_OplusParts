/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.lineageos.settings.R
import org.lineageos.settings.pluskey.PlusKeySettingsActivity
import org.lineageos.settings.pocket.PocketModeService
import org.lineageos.settings.pocket.PocketModeSettingsActivity

/**
 * Hides Settings entries for features the device doesn't have, by disabling their activity.
 * Settings only shows IA_SETTINGS entries whose component is enabled.
 */
object SettingsEntries {
    private const val TAG = "SettingsEntries"
    private const val PLUS_KEY_HANDLER = "org.lineageos.settings.pluskey.KeyHandler"

    @JvmStatic
    fun update(context: Context) {
        // The Plus key works when its handler is in the device's key handler list.
        setEnabled(
            context, PlusKeySettingsActivity::class.java,
            context.resources.getStringArray(R.array.config_partsKeyHandlers)
                .contains(PLUS_KEY_HANDLER)
        )
        setEnabled(
            context, PocketModeSettingsActivity::class.java,
            PocketModeService.isSupported(context)
        )
    }

    private fun setEnabled(context: Context, cls: Class<*>, enabled: Boolean) {
        val component = ComponentName(context, cls)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(component) == state) return
        Log.i(TAG, "${cls.simpleName}: ${if (enabled) "shown" else "hidden"}")
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
