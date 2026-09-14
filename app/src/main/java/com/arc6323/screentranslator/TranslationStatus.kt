package com.arc6323.screentranslator

/** Main-thread status. No screen text or images are persisted. */
object TranslationStatus {
    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val message: String = "Перевод выключен",
        val elapsedMs: Long? = null,
        val needsCapture: Boolean = false
    )
    var state = State()
        private set
    private val listeners = linkedSetOf<() -> Unit>()
    fun update(next: State) { state = next; listeners.toList().forEach { it() } }
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
}
