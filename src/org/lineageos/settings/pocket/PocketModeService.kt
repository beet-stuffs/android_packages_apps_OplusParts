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
import android.telecom.TelecomManager
import android.util.Log
import android.view.Display
import android.os.UserHandle
import android.provider.Settings
import org.lineageos.settings.R

/**
 * Blocks touches when the device is woken up while it is in a pocket.
 *
 * Only framework APIs are used so this works the same on every device:
 *  - Screen off, doze and AOD: SystemUI's doze triggers already drop taps, pick-up and UDFPS
 *    long-press events and pause AOD while the proximity sensor is covered, and
 *    PocketKeyHandler drops touchscreen keys (double tap to wake, gestures) before any other
 *    key handler sees them. This service keeps the pocket state while the screen is not on so
 *    it is known the moment the screen wakes up.
 *  - Woken up anyway (power key, fingerprint, touch gesture keys) while covered: a secure
 *    overlay swallows every touch until the sensor is uncovered. If it stays covered the
 *    device goes back to sleep.
 *  - Under-display fingerprint sensors capture on their own, so fingerprint unlock is turned
 *    off while in the pocket, see PocketFingerprintBlocker.
 */
class PocketModeService : Service(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private var created = false
    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private lateinit var sensorManager: SensorManager
    private lateinit var overlay: PocketModeOverlay
    private lateinit var fingerprintBlocker: PocketFingerprintBlocker
    private var sensor: Sensor? = null

    private var enabled = false
    private var sensorRegistered = false
    private var displayState = Display.STATE_UNKNOWN
    private var near = false
    private var screenOnTime = 0L

    private var wakeCheckTimeoutMs = 0L
    private var exitDelayMs = 0L
    private var sleepTimeoutMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateDisplayState()
        }
    }

    private val hideOverlayRunnable = Runnable {
        dlog("sensor uncovered, unblocking touches")
        hideOverlay()
    }

    private val sleepRunnable = Runnable {
        if (overlay.isShowing && near) {
            Log.i(TAG, "still in the pocket, going back to sleep")
            powerManager.goToSleep(SystemClock.uptimeMillis())
        }
    }

    private val stopWakeCheckRunnable = Runnable {
        // No "near" reported shortly after waking up: not in a pocket.
        if (!overlay.isShowing) updateSensorRegistration()
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)!!
        powerManager = getSystemService(PowerManager::class.java)!!
        sensorManager = getSystemService(SensorManager::class.java)!!
        fingerprintBlocker = PocketFingerprintBlocker(this)
        overlay = PocketModeOverlay(this, resources.getInteger(R.integer.config_pocketModeDismissHoldMs)) {
            Log.i(TAG, "overlay dismissed by the user")
            hideOverlay()
        }

        sensor = findSensor(this)
        wakeCheckTimeoutMs = resources.getInteger(R.integer.config_pocketModeWakeCheckTimeoutMs).toLong()
        exitDelayMs = resources.getInteger(R.integer.config_pocketModeExitDelayMs).toLong()
        sleepTimeoutMs = resources.getInteger(R.integer.config_pocketModeSleepTimeoutMs).toLong()
        created = true

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(KEY_POCKET_MODE), false, settingObserver, UserHandle.USER_ALL
        )
        displayManager.registerDisplayListener(displayListener, handler)
        updateEnabled()
    }

    override fun onDestroy() {
        created = false
        contentResolver.unregisterContentObserver(settingObserver)
        displayManager.unregisterDisplayListener(displayListener)
        setSensorRegistered(false)
        hideOverlay()
        fingerprintBlocker.restore()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private val settingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateEnabled()
    }

    private fun updateEnabled() {
        enabled = isEnabled(this) && sensor != null
        dlog("updateEnabled: enabled=$enabled sensor=${sensor?.stringType}")
        if (!enabled) hideOverlay()
        updateDisplayState()
    }

    private fun updateDisplayState() {
        val state = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return
        val wasOn = displayState == Display.STATE_ON
        val isOn = state == Display.STATE_ON
        displayState = state
        dlog("display state ${Display.stateToString(state)}")

        if (isOn && !wasOn) {
            screenOnTime = SystemClock.uptimeMillis()
            if (enabled && near) {
                onWokeUpInPocket()
            } else {
                handler.removeCallbacks(stopWakeCheckRunnable)
                handler.postDelayed(stopWakeCheckRunnable, wakeCheckTimeoutMs)
            }
        } else if (!isOn) {
            // OFF, DOZE, DOZE_SUSPEND (AOD): SystemUI handles the screen, keep tracking.
            hideOverlay()
        }
        updateSensorRegistration()
    }

    // Track the pocket while the screen is not on, while touches are blocked and shortly
    // after waking up.
    private fun updateSensorRegistration() {
        val inWakeCheck = displayState == Display.STATE_ON &&
                SystemClock.uptimeMillis() - screenOnTime < wakeCheckTimeoutMs
        setSensorRegistered(
            enabled && (displayState != Display.STATE_ON || overlay.isShowing || inWakeCheck)
        )
        updateFingerprintBlock()
    }

    // In the pocket with the screen off, or with touches blocked after waking up.
    private fun updateFingerprintBlock() {
        fingerprintBlocker.setBlocked(
            enabled && near && (displayState != Display.STATE_ON || overlay.isShowing)
        )
    }

    private fun setSensorRegistered(register: Boolean) {
        if (register == sensorRegistered) return
        sensorRegistered = register
        if (register) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } else {
            sensorManager.unregisterListener(this)
            near = false
        }
        dlog("sensor registered=$register")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sensor ?: return
        near = event.values[0] < s.maximumRange
        dlog("onSensorChanged: near=$near display=${Display.stateToString(displayState)}")

        if (near) {
            handler.removeCallbacks(hideOverlayRunnable)
            if (displayState == Display.STATE_ON && !overlay.isShowing &&
                    SystemClock.uptimeMillis() - screenOnTime < wakeCheckTimeoutMs) {
                onWokeUpInPocket()
            }
        } else if (overlay.isShowing) {
            handler.removeCallbacks(hideOverlayRunnable)
            handler.postDelayed(hideOverlayRunnable, exitDelayMs)
        }
        updateFingerprintBlock()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun onWokeUpInPocket() {
        if (isInCall()) {
            dlog("in a call, not blocking touches")
            return
        }
        Log.i(TAG, "woke up in the pocket, blocking touches")
        handler.removeCallbacks(stopWakeCheckRunnable)
        overlay.show()
        if (sleepTimeoutMs > 0) {
            handler.removeCallbacks(sleepRunnable)
            handler.postDelayed(sleepRunnable, sleepTimeoutMs)
        }
        updateSensorRegistration()
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.removeCallbacks(sleepRunnable)
        if (overlay.isShowing) overlay.hide()
        if (created) updateSensorRegistration()
    }

    private fun isInCall() = runCatching {
        getSystemService(TelecomManager::class.java)?.isInCall == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "PocketMode"
        // Settings.System key of the pocket mode switch.
        const val KEY_POCKET_MODE = "pocket_mode_enabled"

        private fun dlog(msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg)
        }

        fun findSensor(context: Context): Sensor? {
            val res = context.resources
            if (!res.getBoolean(R.bool.config_pocketModeSupported)) return null
            val sensorManager = context.getSystemService(SensorManager::class.java) ?: return null
            val type = res.getString(R.string.config_pocketModeSensorType)
            val candidates = if (type.isEmpty()) {
                sensorManager.getSensorList(Sensor.TYPE_PROXIMITY)
            } else {
                sensorManager.getSensorList(Sensor.TYPE_ALL).filter { it.stringType == type }
            }
            return candidates.firstOrNull { it.isWakeUpSensor } ?: candidates.firstOrNull()
        }

        fun isSupported(context: Context) = findSensor(context) != null

        fun isEnabled(context: Context) = Settings.System.getIntForUser(
            context.contentResolver, KEY_POCKET_MODE,
            if (context.resources.getBoolean(R.bool.config_pocketModeDefaultEnabled)) 1 else 0,
            UserHandle.USER_CURRENT
        ) != 0

        fun setEnabled(context: Context, enabled: Boolean) {
            Settings.System.putIntForUser(
                context.contentResolver, KEY_POCKET_MODE, if (enabled) 1 else 0,
                UserHandle.USER_CURRENT
            )
        }

        @JvmStatic
        fun startIfSupported(context: Context) {
            PocketFingerprintBlocker.restoreIfNeeded(context)
            if (isSupported(context)) {
                context.startService(Intent(context, PocketModeService::class.java))
            }
        }
    }
}
