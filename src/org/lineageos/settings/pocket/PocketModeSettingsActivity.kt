/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R

class PocketModeSettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                PocketModeSettingsFragment(),
                TAG,
            )
            .commit()
    }

    class PocketModeSettingsFragment : SettingsBasePreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pocket_mode_settings, rootKey)
            // Stored in Settings.System, not in our preferences.
            findPreference<MainSwitchPreference>(PREF_POCKET_MODE)?.apply {
                isPersistent = false
                isChecked = PocketModeService.isEnabled(requireContext())
                isEnabled = PocketModeService.isSupported(requireContext())
                setOnPreferenceChangeListener { _, newValue ->
                    PocketModeService.setEnabled(requireContext(), newValue as Boolean)
                    true
                }
            }
        }
    }

    companion object {
        private const val TAG = "PocketModeSettingsActivity"
        private const val PREF_POCKET_MODE = "pocket_mode"
    }
}
