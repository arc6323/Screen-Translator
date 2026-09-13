package com.arc6323.screentranslator

/** Main-thread observable status; no screen text or screenshots are persisted. */
object TranslationStatus {
    data class State(
        val running: Boolean = false,
        val frozen: Boolean = false,
        val message: String = "Перевод выключен",
        val elapsedMs: Long? = null
    )
    var state = State()
        private set
    private val listeners = linkedSetOf<() -> Unit>()
    fun update(next: State) {
        state = next
        listeners.toList().forEach { it() }
    }
    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
}
