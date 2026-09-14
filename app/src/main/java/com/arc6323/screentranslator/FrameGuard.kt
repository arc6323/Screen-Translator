package com.arc6323.screentranslator

/** Only the current frame may publish, finish, or schedule more work. */
class FrameGuard {
    private var serial = 0L
    @Volatile private var active: Long? = null
    @Volatile private var stopped = true
    fun start() { serial++; active = null; stopped = false }
    fun begin(): Long? {
        if (stopped || active != null) return null
        return (++serial).also { active = it }
    }
    fun accepts(token: Long) = !stopped && active == token
    fun finish(token: Long): Boolean {
        if (!accepts(token)) return false
        active = null
        return true
    }
    fun invalidate() { serial++; active = null }
    fun stop() { invalidate(); stopped = true }
}
