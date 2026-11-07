/*
 * Copyright (C) 2021-2026 The LineageOS Project
 * Copyright (C) 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pluskey

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.ZenModeConfig
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import com.android.internal.app.AssistUtils
import com.android.internal.os.DeviceKeyHandler
import com.android.internal.util.ScreenshotHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val screenshotHelper = ScreenshotHelper(context)
    private val vibrator = context.getSystemService(Vibrator::class.java)!!

    // Settings package context of the current user, recreated on user switch
    @Volatile private var packageContext: Pair<Int, Context>? = null

    private fun getPackageContext(): Context? {
        val userId = ActivityManager.getCurrentUser()
        packageContext?.let { (cachedUserId, pkgCtx) ->
            if (cachedUserId == userId) return pkgCtx
        }
        return try {
            context
                .createPackageContextAsUser(PACKAGE_NAME, 0, UserHandle.of(userId))
                .createDeviceProtectedStorageContext()
                .also { packageContext = userId to it }
        } catch (e: Exception) {
            // Don't cache failures, and never fall back to system_server's own storage.
            Log.e(TAG, "Failed to create package context for user $userId", e)
            null
        }
    }

    private val sharedPreferences: SharedPreferences?
        get() =
            getPackageContext()
                ?.getSharedPreferences(
                    PACKAGE_NAME + "_preferences",
                    Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
                )

    private val executorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isTorchOn = false

    // Actions snapshotted on the initial ACTION_DOWN of the current press
    private var singlePressAction = ACTION_NONE
    private var longPressAction = ACTION_NONE

    private val pressState = AtomicInteger(PRESS_IDLE)
    private var longPressRunnable: Runnable? = null

    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                isTorchOn = enabled
            }
        }

    init {
        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register torch callback", e)
        }
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? =
        if (isPlusKey(event)) handlePlusKeyEvent(event) else event

    // Runs for every key event on the input path: keep the cheap checks first and
    // only resolve the input device when the keycode can actually match.
    private fun isPlusKey(event: KeyEvent): Boolean =
        event.scanCode == SCANCODE_PLUS_KEY ||
            (event.keyCode == KeyEvent.KEYCODE_ASSIST &&
                event.device?.name?.contains("gpio-keys") == true)

    private fun handlePlusKeyEvent(event: KeyEvent): KeyEvent? {
        // Preferences live in another package's storage: read them once per press on
        // the initial DOWN and reuse the snapshot for repeats and the UP event, so the
        // whole gesture is handled consistently even if the settings change mid-press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            singlePressAction = getActionPreference(CONFIG_PLUS_KEY_SINGLE_PRESS, ACTION_CYCLE_RINGER)
            longPressAction = getActionPreference(CONFIG_PLUS_KEY_LONG_PRESS, ACTION_NONE)
        }

        // Default assistant behavior: if no custom action, let system handle it
        if (singlePressAction == ACTION_DEFAULT && longPressAction == ACTION_NONE) {
            return event
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    removeLongPressTimer()

                    if (longPressAction != ACTION_NONE) {
                        // Long press configured: post timer
                        val action = longPressAction
                        pressState.set(PRESS_PENDING)
                        val runnable = Runnable {
                            // Only fire if the key hasn't been released in the meantime.
                            if (pressState.compareAndSet(PRESS_PENDING, PRESS_LONG_PRESSED)) {
                                executeAction(action)
                            }
                        }
                        longPressRunnable = runnable
                        mainHandler.postDelayed(
                            runnable,
                            ViewConfiguration.getLongPressTimeout().toLong(),
                        )
                    } else {
                        // Immediate execution on DOWN
                        pressState.set(PRESS_IDLE)
                        executeAction(singlePressAction)
                    }
                }
                return null
            }

            KeyEvent.ACTION_UP -> {
                removeLongPressTimer()

                // The long press timer runs on the main looper while key events arrive on
                // the input thread: whoever moves the state out of PENDING first wins, so a
                // release racing with the timeout never triggers both actions.
                // A canceled up event (FLAG_CANCELED) means the gesture was aborted:
                // don't fire the single-press action.
                if (pressState.compareAndSet(PRESS_PENDING, PRESS_IDLE) && !event.isCanceled) {
                    executeAction(singlePressAction)
                }
                return null
            }
        }

        return null
    }

    private fun removeLongPressTimer() {
        longPressRunnable?.let {
            mainHandler.removeCallbacks(it)
            longPressRunnable = null
        }
    }

    private fun getActionPreference(key: String, defValue: Int): Int =
        try {
            sharedPreferences?.getString(key, null)?.toInt() ?: defValue
        } catch (e: Exception) {
            defValue
        }

    private fun executeAction(action: Int) {
        when (action) {
            ACTION_DEFAULT -> launchAssistant()
            ACTION_CYCLE_RINGER -> cycleRingerMode()
            ACTION_TORCH -> toggleTorch()
            ACTION_DND -> toggleDnd()
            ACTION_CAMERA -> openCamera()
            ACTION_SCREENSHOT -> takeScreenshot()
            ACTION_MUTE_MEDIA -> toggleMediaMute()
        }
    }

    private fun vibrate() {
        if (vibrationFeedbackEnabled()) {
            vibrator.vibrate(MODE_NORMAL_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
        }
    }

    private fun vibrateForRingerMode(mode: Int) {
        if (!vibrationFeedbackEnabled()) {
            return
        }
        val effect =
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> MODE_SILENT_EFFECT
                AudioManager.RINGER_MODE_VIBRATE -> MODE_VIBRATION_EFFECT
                else -> MODE_NORMAL_EFFECT
            }
        vibrator.vibrate(effect, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
    }

    private fun vibrationFeedbackEnabled(): Boolean =
        sharedPreferences?.getBoolean(CONFIG_VIBRATION_FEEDBACK, true) ?: true

    private fun cycleRingerMode() {
        executorService.submit {
            // Read the current mode on the executor too: reading it on the input thread
            // raced with the previous press' pending update, so quick presses could
            // compute the same next mode and skip a step.
            val nextMode =
                when (audioManager.ringerModeInternal) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }

            audioManager.ringerModeInternal = nextMode

            vibrateForRingerMode(nextMode)
        }
    }

    private fun toggleTorch() {
        executorService.submit {
            try {
                val cameraId =
                    cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager
                            .getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return@submit

                val newTorchState = !isTorchOn
                cameraManager.setTorchMode(cameraId, newTorchState)
                isTorchOn = newTorchState

                vibrate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle torch", e)
            }
        }
    }

    private fun toggleDnd() {
        executorService.submit {
            try {
                if (notificationManager.zenMode != Settings.Global.ZEN_MODE_OFF) {
                    notificationManager.setZenMode(
                        Settings.Global.ZEN_MODE_OFF,
                        null,
                        TAG,
                        /* fromUser= */ true,
                    )
                } else {
                    // Same toggle as the system DND tile: activating the manual DND mode keeps
                    // whatever the user configured in DND settings.
                    val zenDuration =
                        try {
                            Settings.Secure.getInt(
                                context.contentResolver,
                                Settings.Secure.ZEN_DURATION,
                            )
                        } catch (e: Exception) {
                            Settings.Secure.ZEN_DURATION_FOREVER
                        }

                    val durationConditionId =
                        when (zenDuration) {
                            // "Ask every time" can't be prompted from here, run until off instead.
                            Settings.Secure.ZEN_DURATION_FOREVER, Settings.Secure.ZEN_DURATION_PROMPT ->
                                null
                            else ->
                                ZenModeConfig.toTimeCondition(
                                        context,
                                        zenDuration,
                                        ActivityManager.getCurrentUser(),
                                        true,
                                    )
                                    .id
                        }

                    notificationManager.setZenMode(
                        Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        durationConditionId,
                        TAG,
                        /* fromUser= */ true,
                    )
                }

                vibrate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle DND", e)
            }
        }
    }

    private fun openCamera() {
        executorService.submit {
            try {
                // Starting the camera behind an off display leaves it invisible:
                // wake the screen first, like the system's own camera shortcuts
                // (WAKE_REASON_CAMERA_LAUNCH). Other actions stay screen-off safe.
                val powerManager = context.getSystemService(PowerManager::class.java)
                if (powerManager?.isInteractive == false) {
                    powerManager.wakeUp(
                        SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                        TAG,
                    )
                }

                val keyguardManager = context.getSystemService(KeyguardManager::class.java)
                val isLocked = keyguardManager?.isKeyguardLocked ?: false
                val action =
                    if (isLocked) {
                        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                    } else {
                        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    }
                val intent =
                    Intent(action).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                context.startActivityAsUser(intent, UserHandle.CURRENT)

                vibrate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open camera", e)
            }
        }
    }

    private fun takeScreenshot() {
        executorService.submit {
            try {
                screenshotHelper.takeScreenshot(
                    WindowManager.ScreenshotSource.SCREENSHOT_OTHER,
                    mainHandler,
                    null,
                )

                vibrate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to take screenshot", e)
            }
        }
    }

    private fun toggleMediaMute() {
        executorService.submit {
            try {
                val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                val newMute = !isMuted
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (newMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )

                vibrate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle media mute", e)
            }
        }
    }

    private fun launchAssistant() {
        executorService.submit {
            try {
                // Same path as PhoneWindowManager.launchAssistAction(): starts the default
                // voice assistant through the VoiceInteractionManagerService instead of
                // hoping ACTION_VOICE_COMMAND resolves to an activity.
                val args =
                    Bundle().apply {
                        putInt(AssistUtils.INVOCATION_TYPE_KEY, AssistUtils.INVOCATION_TYPE_ASSIST_BUTTON)
                    }
                context.getSystemService(SearchManager::class.java)?.launchAssist(args)
                    ?: Log.e(TAG, "SearchManager unavailable, cannot launch assistant")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch voice assistant", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlusKeyHandler"

        // Long press state of the current key press
        private const val PRESS_IDLE = 0
        private const val PRESS_PENDING = 1
        private const val PRESS_LONG_PRESSED = 2
        private const val PACKAGE_NAME = "org.lineageos.settings"

        // Hardware scancode for OnePlus 15 (infiniti) Plus key
        private const val SCANCODE_PLUS_KEY = 735

        // Preference keys
        private const val CONFIG_PLUS_KEY_SINGLE_PRESS = "config_plus_key_single_press"
        private const val CONFIG_PLUS_KEY_LONG_PRESS = "config_plus_key_long_press"
        private const val CONFIG_VIBRATION_FEEDBACK = "config_vibration_feedback"

        // Action IDs (values must match the entry values in plus_key_action_entry_values)
        private const val ACTION_NONE = -1
        private const val ACTION_DEFAULT = 0
        private const val ACTION_CYCLE_RINGER = 1
        private const val ACTION_TORCH = 2
        private const val ACTION_DND = 3
        private const val ACTION_CAMERA = 4
        private const val ACTION_SCREENSHOT = 5
        private const val ACTION_MUTE_MEDIA = 6

        // Vibration attributes
        private val HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

        // Vibration effects
        private val MODE_NORMAL_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val MODE_VIBRATION_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
        private val MODE_SILENT_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_TICK)
    }
}
