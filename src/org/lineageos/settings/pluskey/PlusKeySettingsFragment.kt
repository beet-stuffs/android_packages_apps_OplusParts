/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pluskey

import android.os.Bundle
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.settings.R

class PlusKeySettingsFragment : SettingsBasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.plus_key_panel, rootKey)
    }
}
