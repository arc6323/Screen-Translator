package com.arc6323.screentranslator

data class CaptureGeometry(
    val captureWidth: Int, val captureHeight: Int,
    val screenWidth: Int, val screenHeight: Int,
    val windowX: Int = 0, val windowY: Int = 0
) {
    init {
        require(captureWidth > 0 && captureHeight > 0 && screenWidth > 0 && screenHeight > 0)
    }
    val scaleX get() = screenWidth.toFloat() / captureWidth
    val scaleY get() = screenHeight.toFloat() / captureHeight
    fun x(captureX: Float) = captureX * scaleX - windowX
    fun y(captureY: Float) = captureY * scaleY - windowY
}
