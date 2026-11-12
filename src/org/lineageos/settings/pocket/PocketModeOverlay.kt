/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.pocket

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.lineageos.settings.R

/**
 * Full screen window that consumes every touch while the device is in a pocket.
 *
 * TYPE_KEYGUARD_DIALOG sits above the keyguard and SystemUI's UDFPS overlay, and unlike
 * TYPE_SECURE_SYSTEM_OVERLAY the policy lets it receive touch events, so a hold dismisses
 * the overlay. The fingerprint sensor doesn't need touches to capture, see
 * PocketFingerprintBlocker.
 */
internal class PocketModeOverlay(
    private val context: Context,
    private val dismissHoldMs: Int,
    private val onDismissed: () -> Unit,
    private val onTouched: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)!!
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    val isShowing: Boolean
        get() = view != null

    private val dismissRunnable = Runnable { onDismissed() }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "PocketMode"
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (view != null) return
        view = LayoutInflater.from(context).inflate(R.layout.pocket_mode_overlay, null).apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        onTouched()
                        if (dismissHoldMs > 0) {
                            handler.postDelayed(dismissRunnable, dismissHoldMs.toLong())
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        handler.removeCallbacks(dismissRunnable)
                }
                true // swallow everything
            }
        }
        windowManager.addView(view, layoutParams)
    }

    fun hide() {
        handler.removeCallbacks(dismissRunnable)
        view?.let { windowManager.removeViewImmediate(it) }
        view = null
    }
}
