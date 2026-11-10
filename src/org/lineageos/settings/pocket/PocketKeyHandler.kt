/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

/**
 * Drops touchscreen key events (double tap to wake, touchscreen gestures) while pocket mode is
 * enabled, the screen is off or dozing and the proximity sensor is covered.
 *
 * Runs in system_server through PartsKeyHandler, which devices list before other key handlers
 * (e.g. TouchGestures) so they never see the dropped events. With pocket mode off it passes
 * everything through.
 */
class PocketKeyHandler(private val context: Context) : DeviceKeyHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val sensorManager = context.getSystemService(SensorManager::class.java)!!

    // Our resources, through a package context since this runs in system_server.
    private val packageContext: Context? = try {
        context.createPackageContext(PACKAGE_NAME, 0)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create package context", e)
        null
    }

    private val sensor: Sensor? = packageContext?.let { PocketModeService.findSensor(it) }
    private val defaultEnabled = packageContext?.let {
        it.resources.getBoolean(org.lineageos.settings.R.bool.config_pocketModeDefaultEnabled)
    } ?: false

    // Input devices that send gesture keys without being the touchscreen itself.
    private val extraGestureDevices: Set<String> = packageContext?.let {
        it.resources.getStringArray(
            org.lineageos.settings.R.array.config_pocketModeGestureInputDevices
        ).toSet()
    } ?: emptySet()

    @Volatile private var enabled = false
    private var sensorRegistered = false
    @Volatile private var near = false

    // Key codes whose ACTION_DOWN was dropped, so the matching ACTION_UP is dropped too.
    private val droppedKeys = mutableSetOf<Int>()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            near = event.values[0] < (sensor?.maximumRange ?: return)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateSensorRegistration()
    }

    private val settingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateEnabled()
    }

    init {
        if (sensor != null) {
            context.registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, null, handler)
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(PocketModeService.KEY_POCKET_MODE), false,
                settingObserver, UserHandle.USER_ALL
            )
            updateEnabled()
        }
    }

    private fun updateEnabled() {
        enabled = Settings.System.getIntForUser(
            context.contentResolver, PocketModeService.KEY_POCKET_MODE,
            if (defaultEnabled) 1 else 0, UserHandle.USER_CURRENT
        ) != 0
        updateSensorRegistration()
    }

    // Track the pocket while the screen is off or dozing, the only time these events matter.
    private fun updateSensorRegistration() {
        val register = enabled && !powerManager.isInteractive
        if (register == sensorRegistered) return
        sensorRegistered = register
        if (register) {
            sensorManager.registerListener(
                sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler
            )
        } else {
            sensorManager.unregisterListener(sensorListener)
            near = false
        }
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (!isFromTouchscreen(event)) return event

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && droppedKeys.remove(keyCode)) {
            return null
        }
        if (event.action == KeyEvent.ACTION_DOWN && enabled && near &&
                !powerManager.isInteractive) {
            Log.i(TAG, "In a pocket, dropping touchscreen key ${KeyEvent.keyCodeToString(keyCode)}")
            droppedKeys.add(keyCode)
            return null
        }
        return event
    }

    private fun isFromTouchscreen(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                device.name in extraGestureDevices
    }

    companion object {
        private const val TAG = "PocketKeyHandler"
        private const val PACKAGE_NAME = "org.lineageos.settings"
    }
}
