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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val vibrator = context.getSystemService(Vibrator::class.java)!!
    private val screenshotHelper = ScreenshotHelper(context)

    // Resolved on first use: these services may not be published yet when
    // PhoneWindowManager instantiates the key handler.
    private val keyguardManager by lazy { context.getSystemService(KeyguardManager::class.java)!! }
    private val powerManager by lazy { context.getSystemService(PowerManager::class.java)!! }
    private val searchManager by lazy { context.getSystemService(SearchManager::class.java)!! }

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

    // Torch state per camera id, as reported by the torch callback
    private val torchStates = ConcurrentHashMap<String, Boolean>()

    // Camera used for the torch, looked up once on the executor
    private var flashCameraId: String? = null

    // Preferences snapshotted on the initial ACTION_DOWN of the current press
    private var singlePressAction = ACTION_NONE
    private var longPressAction = ACTION_NONE
    @Volatile private var vibrationFeedback = true

    // Set while a long press timer is armed. The timer (main looper) and ACTION_UP (input
    // thread) race to clear it: whoever wins decides between the long and single press.
    private val longPressPending = AtomicBoolean(false)
    private var longPressRunnable: Runnable? = null

    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torchStates[cameraId] = enabled
            }
        }

    init {
        // Connecting to cameraserver is binder work: keep it off the system_server
        // startup path, and get torch updates on the executor rather than the main looper.
        executorService.submit {
            try {
                cameraManager.registerTorchCallback(executorService, torchCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register torch callback", e)
            }
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
        val isInitialDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0

        // Read the preferences once per press and reuse them for repeats and the UP event,
        // so the whole gesture is handled consistently even if the settings change mid-press.
        if (isInitialDown) {
            val prefs = sharedPreferences
            singlePressAction = prefs.getAction(CONFIG_PLUS_KEY_SINGLE_PRESS, ACTION_CYCLE_RINGER)
            longPressAction = prefs.getAction(CONFIG_PLUS_KEY_LONG_PRESS, ACTION_NONE)
            vibrationFeedback = prefs?.getBoolean(CONFIG_VIBRATION_FEEDBACK, true) ?: true
        }

        // Nothing to intercept: let the framework handle the key (assistant)
        if (singlePressAction == ACTION_ASSISTANT && longPressAction == ACTION_NONE) {
            return event
        }

        if (isInitialDown) {
            removeLongPressTimer()
            longPressPending.set(longPressAction != ACTION_NONE)

            if (longPressAction == ACTION_NONE) {
                executeAction(singlePressAction)
            } else {
                val action = longPressAction
                longPressRunnable =
                    Runnable { if (longPressPending.compareAndSet(true, false)) executeAction(action) }
                        .also {
                            mainHandler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                        }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            removeLongPressTimer()

            // A canceled UP (FLAG_CANCELED) means the gesture was aborted: no single press.
            if (longPressPending.compareAndSet(true, false) && !event.isCanceled) {
                executeAction(singlePressAction)
            }
        }

        return null
    }

    private fun removeLongPressTimer() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun SharedPreferences?.getAction(key: String, defValue: Int): Int =
        this?.getString(key, null)?.toIntOrNull() ?: defValue

    private fun executeAction(action: Int) {
        if (action == ACTION_NONE) {
            return
        }
        executorService.submit {
            try {
                when (action) {
                    ACTION_ASSISTANT -> launchAssistant()
                    ACTION_CYCLE_RINGER -> cycleRingerMode()
                    ACTION_TORCH -> toggleTorch()
                    ACTION_DND -> toggleDnd()
                    ACTION_CAMERA -> openCamera()
                    ACTION_SCREENSHOT -> takeScreenshot()
                    ACTION_MUTE_MEDIA -> toggleMediaMute()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute action $action", e)
            }
        }
    }

    private fun vibrate(effect: VibrationEffect = MODE_NORMAL_EFFECT) {
        if (vibrationFeedback) {
            vibrator.vibrate(effect, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
        }
    }

    // All actions below run on the single threaded executor, so read-modify-write
    // sequences (ringer mode, torch state) can't interleave between quick presses.

    private fun cycleRingerMode() {
        val nextMode =
            when (audioManager.ringerModeInternal) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        audioManager.ringerModeInternal = nextMode

        vibrate(
            when (nextMode) {
                AudioManager.RINGER_MODE_SILENT -> MODE_SILENT_EFFECT
                AudioManager.RINGER_MODE_VIBRATE -> MODE_VIBRATION_EFFECT
                else -> MODE_NORMAL_EFFECT
            }
        )
    }

    private fun toggleTorch() {
        val cameraId =
            flashCameraId
                ?: cameraManager.cameraIdList
                    .firstOrNull { id ->
                        cameraManager
                            .getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    ?.also { flashCameraId = it }
                ?: return

        // Only look at the state of the camera we toggle: other cameras (e.g. a
        // front flash) report their own torch changes through the same callback.
        val newTorchState = torchStates[cameraId] != true
        cameraManager.setTorchMode(cameraId, newTorchState)
        torchStates[cameraId] = newTorchState

        vibrate()
    }

    private fun toggleDnd() {
        if (notificationManager.zenMode != Settings.Global.ZEN_MODE_OFF) {
            notificationManager.setZenMode(Settings.Global.ZEN_MODE_OFF, null, TAG, /* fromUser= */ true)
        } else {
            // Same toggle as the system DND tile: activating the manual DND mode keeps
            // whatever the user configured in DND settings.
            val zenDuration =
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ZEN_DURATION,
                    Settings.Secure.ZEN_DURATION_FOREVER,
                )
            val durationConditionId =
                when (zenDuration) {
                    // "Ask every time" can't be prompted from here, run until off instead.
                    Settings.Secure.ZEN_DURATION_FOREVER, Settings.Secure.ZEN_DURATION_PROMPT -> null
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
    }

    private fun openCamera() {
        // Starting the camera behind an off display leaves it invisible: wake the screen
        // first, like the system's own camera shortcuts. Other actions stay screen-off safe.
        if (!powerManager.isInteractive) {
            powerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_CAMERA_LAUNCH, TAG)
        }

        val action =
            if (keyguardManager.isKeyguardLocked) {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
            } else {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            }
        context.startActivityAsUser(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            UserHandle.CURRENT,
        )

        vibrate()
    }

    private fun takeScreenshot() {
        screenshotHelper.takeScreenshot(WindowManager.ScreenshotSource.SCREENSHOT_OTHER, mainHandler, null)

        vibrate()
    }

    private fun toggleMediaMute() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)

        vibrate()
    }

    private fun launchAssistant() {
        // Same path as PhoneWindowManager.launchAssistAction(): starts the default voice
        // assistant through the VoiceInteractionManagerService.
        searchManager.launchAssist(
            Bundle().apply {
                putInt(AssistUtils.INVOCATION_TYPE_KEY, AssistUtils.INVOCATION_TYPE_ASSIST_BUTTON)
            }
        )
    }

    companion object {
        private const val TAG = "PlusKeyHandler"
        private const val PACKAGE_NAME = "org.lineageos.settings"

        // Hardware scancode for OnePlus 15 (infiniti) Plus key
        private const val SCANCODE_PLUS_KEY = 735

        // Preference keys, must match res/xml/plus_key_panel.xml
        private const val CONFIG_PLUS_KEY_SINGLE_PRESS = "config_plus_key_single_press"
        private const val CONFIG_PLUS_KEY_LONG_PRESS = "config_plus_key_long_press"
        private const val CONFIG_VIBRATION_FEEDBACK = "config_vibration_feedback"

        // Action IDs, must match plus_key_action_entry_values in res/values/arrays.xml
        private const val ACTION_NONE = -1
        private const val ACTION_ASSISTANT = 0
        private const val ACTION_CYCLE_RINGER = 1
        private const val ACTION_TORCH = 2
        private const val ACTION_DND = 3
        private const val ACTION_CAMERA = 4
        private const val ACTION_SCREENSHOT = 5
        private const val ACTION_MUTE_MEDIA = 6

        private val HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

        private val MODE_NORMAL_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val MODE_VIBRATION_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
        private val MODE_SILENT_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_TICK)
    }
}
