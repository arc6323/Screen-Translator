package com.arc6323.screentranslator

object LoopTiming {
    /** Processing time is part of the interval, rather than adding a second wait afterwards. */
    fun nextDelay(elapsedMs: Long, unchanged: Boolean): Long =
        ((if (unchanged) 260L else 120L) - elapsedMs.coerceAtLeast(0)).coerceAtLeast(16L)
    fun touchAlpha(systemMaximum: Float): Float =
        (minOf(0.8f, if (systemMaximum.isFinite()) systemMaximum else 0.8f) - 0.04f).coerceIn(0f, 0.76f)
}
