/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.content.Context
import com.android.settingslib.drawer.CategoryKey
import com.android.settingslib.drawer.EntryController
import com.android.settingslib.drawer.EntriesProvider
import com.android.settingslib.drawer.ProviderSwitch
import org.lineageos.settings.R

/**
 * Injects the Pocket mode switch into Settings, so it is a plain toggle in Display >
 * Other display controls instead of a page.
 */
class PocketModeEntriesProvider : EntriesProvider() {
    override fun createEntryControllers(): List<EntryController> =
        listOf(PocketModeSwitch(getContext()!!))

    private class PocketModeSwitch(private val context: Context) : EntryController(),
        ProviderSwitch {
        override fun getKey() = KEY

        override fun getMetaData() = MetaData(CategoryKey.CATEGORY_DISPLAY)
            .setTitle(R.string.pocket_mode_title)
            .setSummary(R.string.pocket_mode_summary)

        override fun isSwitchChecked() = PocketModeService.isEnabled(context)

        override fun onSwitchCheckedChanged(checked: Boolean): Boolean {
            if (!PocketModeService.isSupported(context)) return false
            PocketModeService.setEnabled(context, checked)
            return true
        }

        override fun getSwitchErrorMessage(attemptedChecked: Boolean) =
            context.getString(R.string.pocket_mode_not_supported)

        companion object {
            private const val KEY = "pocket_mode"
        }
    }
}
