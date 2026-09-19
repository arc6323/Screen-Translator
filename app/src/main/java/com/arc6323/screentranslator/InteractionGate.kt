package com.arc6323.screentranslator

/** OUTSIDE has no UP event: resume after contact cooldown and a visually quiet page. */
class InteractionGate {
    private var quietAfter = 0L
    fun touch(now: Long) { quietAfter = maxOf(quietAfter, now + 400) }
    fun motion(now: Long) { quietAfter = maxOf(quietAfter, now + 240) }
    fun delay(now: Long): Long = (quietAfter - now).coerceAtLeast(0)
}
