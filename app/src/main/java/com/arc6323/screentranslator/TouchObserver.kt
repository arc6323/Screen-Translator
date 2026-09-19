package com.arc6323.screentranslator

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Receives only OUTSIDE (initial contact). Never owns or replays another app's gesture. */
class TouchObserver(context: Context, private val touched: () -> Unit) : View(context) {
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) touched()
        return false
    }

    fun windowParams() = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0f
        title = "Translation touch observer"
    }
}
