/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

/**
 * Single entry point for OplusParts' key handlers.
 *
 * PhoneWindowManager loads one class per config_deviceKeyHandlerLibs entry, so instead of listing
 * OplusParts once per handler, devices list this class once, first. It runs the handlers named
 * in config_partsKeyHandlers in order and stops as soon as one consumes the event.
 */
class PartsKeyHandler(context: Context) : DeviceKeyHandler {
    private val handlers: List<DeviceKeyHandler> = loadHandlers(context)

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        var result: KeyEvent = event
        for (handler in handlers) {
            result = try {
                handler.handleKeyEvent(result) ?: return null
            } catch (e: Exception) {
                Log.w(TAG, "Key handler ${handler.javaClass.name} failed", e)
                result
            }
        }
        return result
    }

    private fun loadHandlers(context: Context): List<DeviceKeyHandler> {
        // This runs in system_server, so read our resources through a package context.
        val names = try {
            context.createPackageContext(PACKAGE_NAME, 0)
                .resources.getStringArray(R.array.config_partsKeyHandlers)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read key handler list", e)
            return emptyList()
        }
        return names.mapNotNull { name ->
            try {
                javaClass.classLoader!!.loadClass(name)
                    .getConstructor(Context::class.java)
                    .newInstance(context) as DeviceKeyHandler
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load key handler $name", e)
                null
            }
        }.also { Log.i(TAG, "Loaded key handlers: ${it.map { h -> h.javaClass.simpleName }}") }
    }

    companion object {
        private const val TAG = "PartsKeyHandler"
        private const val PACKAGE_NAME = "org.lineageos.settings"
    }
}
