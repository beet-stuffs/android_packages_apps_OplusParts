/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Display

/**
 * Blocks touches when the device is woken up while it is in a pocket.
 *
 *  - Screen off, doze and AOD: SystemUI's doze triggers already drop taps, pick-up and UDFPS
 *    long-press events while the proximity sensor is covered, and PocketKeyHandler drops
 *    touchscreen keys (double tap to wake, gestures). This service keeps the pocket state
 *    while the screen is not on so it is known the moment the screen wakes up.
 *  - Woken up anyway (power key, fingerprint, gesture keys) while covered: an overlay swallows
 *    every touch until the sensor is uncovered. If it stays covered the device goes back to
 *    sleep.
 *  - Under-display fingerprint sensors capture on their own, so fingerprint unlock is turned
 *    off while in the pocket, see PocketFingerprintBlocker.
 */
class PocketModeService : Service(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager by lazy { getSystemService(DisplayManager::class.java)!! }
    private val powerManager by lazy { getSystemService(PowerManager::class.java)!! }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java)!! }
    private val sensor by lazy { findSensor(this) }
    private val fingerprintBlocker by lazy { PocketFingerprintBlocker(this) }
    private val overlay by lazy {
        PocketModeOverlay(this, onDismissed = ::hideOverlay, onTouched = ::restartSleepTimeout)
    }

    private var enabled = false
    private var sensorRegistered = false
    private var displayState = Display.STATE_UNKNOWN
    private var near = false
    private var screenOnTime = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateDisplayState()
        }
    }

    private val settingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateEnabled()
    }

    private val hideOverlayRunnable = Runnable { hideOverlay() }

    private val sleepRunnable = Runnable {
        if (overlay.isShowing && near) {
            Log.i(TAG, "still in the pocket, going back to sleep")
            powerManager.goToSleep(SystemClock.uptimeMillis())
        }
    }

    // No "near" reported shortly after waking up: not in a pocket.
    private val stopWakeCheckRunnable = Runnable { updateSensorRegistration() }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(KEY_POCKET_MODE), false, settingObserver, UserHandle.USER_ALL
        )
        displayManager.registerDisplayListener(displayListener, handler)
        updateEnabled()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(settingObserver)
        displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        overlay.hide()
        fingerprintBlocker.restore()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateEnabled() {
        enabled = isEnabled(this) && sensor != null
        if (!enabled) hideOverlay()
        updateDisplayState()
    }

    private fun updateDisplayState() {
        val state = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return
        val wasOn = displayState == Display.STATE_ON
        displayState = state

        if (state == Display.STATE_ON) {
            if (!wasOn) {
                screenOnTime = SystemClock.uptimeMillis()
                if (enabled && near) {
                    onWokeUpInPocket()
                } else {
                    handler.removeCallbacks(stopWakeCheckRunnable)
                    handler.postDelayed(stopWakeCheckRunnable, WAKE_CHECK_MS)
                }
            }
        } else {
            // OFF, DOZE, DOZE_SUSPEND (AOD): SystemUI handles the screen, keep tracking.
            hideOverlay()
        }
        updateSensorRegistration()
    }

    // Track the pocket while the screen is not on, while touches are blocked and shortly
    // after waking up.
    private fun updateSensorRegistration() {
        val register = enabled && (displayState != Display.STATE_ON || overlay.isShowing ||
                inWakeCheck())
        if (register != sensorRegistered) {
            sensorRegistered = register
            if (register) {
                sensorManager.registerListener(
                    this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler
                )
            } else {
                sensorManager.unregisterListener(this)
                near = false
            }
        }
        updateFingerprintBlock()
    }

    // In the pocket with the screen off, or with touches blocked after waking up.
    private fun updateFingerprintBlock() {
        fingerprintBlocker.setBlocked(
            enabled && near && (displayState != Display.STATE_ON || overlay.isShowing)
        )
    }

    private fun inWakeCheck() = displayState == Display.STATE_ON &&
            SystemClock.uptimeMillis() - screenOnTime < WAKE_CHECK_MS

    override fun onSensorChanged(event: SensorEvent) {
        near = event.values[0] < event.sensor.maximumRange
        handler.removeCallbacks(hideOverlayRunnable)
        if (near) {
            if (displayState == Display.STATE_ON && !overlay.isShowing && inWakeCheck()) {
                onWokeUpInPocket()
            }
        } else if (overlay.isShowing) {
            handler.postDelayed(hideOverlayRunnable, EXIT_DELAY_MS)
        }
        updateFingerprintBlock()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun onWokeUpInPocket() {
        if (isInCall()) return
        Log.i(TAG, "woke up in the pocket, blocking touches")
        handler.removeCallbacks(stopWakeCheckRunnable)
        overlay.show()
        restartSleepTimeout()
        updateSensorRegistration()
    }

    // (Re)start the timer that puts the device back to sleep if it is still in the pocket.
    private fun restartSleepTimeout() {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, SLEEP_TIMEOUT_MS)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.removeCallbacks(sleepRunnable)
        if (overlay.isShowing) {
            overlay.hide()
            updateSensorRegistration()
        }
    }

    private fun isInCall() = runCatching {
        getSystemService(TelecomManager::class.java)?.isInCall == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "PocketMode"

        // Settings.System key of the pocket mode switch.
        const val KEY_POCKET_MODE = "pocket_mode_enabled"

        // How long after the screen turns on a "near" reading still counts as a wake in the
        // pocket. Covers non wake-up sensors that only report once the system resumed.
        private const val WAKE_CHECK_MS = 500L

        // How long the sensor needs to stay uncovered before touches are allowed again.
        private const val EXIT_DELAY_MS = 300L

        // Put the device back to sleep if it stays covered this long after waking up.
        private const val SLEEP_TIMEOUT_MS = 5000L

        fun findSensor(context: Context): Sensor? {
            val sensors = context.getSystemService(SensorManager::class.java)
                ?.getSensorList(Sensor.TYPE_PROXIMITY) ?: return null
            return sensors.firstOrNull { it.isWakeUpSensor } ?: sensors.firstOrNull()
        }

        fun isSupported(context: Context) = findSensor(context) != null

        fun isEnabled(context: Context) = Settings.System.getIntForUser(
            context.contentResolver, KEY_POCKET_MODE, 1, UserHandle.USER_CURRENT
        ) != 0

        fun setEnabled(context: Context, enabled: Boolean) {
            Settings.System.putIntForUser(
                context.contentResolver, KEY_POCKET_MODE, if (enabled) 1 else 0,
                UserHandle.USER_CURRENT
            )
        }

        @JvmStatic
        fun startIfSupported(context: Context) {
            PocketFingerprintBlocker(context).restore()
            if (isSupported(context)) {
                context.startService(Intent(context, PocketModeService::class.java))
            }
        }
    }
}
