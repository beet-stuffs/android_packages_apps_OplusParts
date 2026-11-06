/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pluskey

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

class PlusKeySettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                PlusKeySettingsFragment(),
                TAG,
            )
            .commit()
    }

    companion object {
        private const val TAG = "PlusKeySettingsActivity"
    }
}
