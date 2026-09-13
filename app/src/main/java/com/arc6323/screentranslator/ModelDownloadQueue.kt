package com.arc6323.screentranslator

/** Single-owner queue. Its owner and backend callbacks must use the same thread. */
class ModelDownloadQueue(
    private val backend: (String, (String?) -> Unit) -> Unit,
    private val changed: (Snapshot) -> Unit = {}
) {
    data class Snapshot(
        val requested: Set<String> = emptySet(),
        val queued: List<String> = emptyList(),
        val current: String? = null,
        val ready: Set<String> = emptySet(),
        val errors: Map<String, String> = emptyMap(),
        val paused: Boolean = false
    ) {
        val active: Boolean get() = current != null || queued.isNotEmpty()
        val finished: Boolean get() = requested.isNotEmpty() && !active
        val successful: Boolean get() = finished && errors.isEmpty()
    }
    private val requested = linkedSetOf<String>()
    private val pending = ArrayDeque<String>()
    private val ready = linkedSetOf<String>()
    private val errors = linkedMapOf<String, String>()
    private var current: String? = null
    private var paused = false
    private var operation = 0L
    fun snapshot() = Snapshot(
        requested.toSet(), pending.toList(), current, ready.toSet(), errors.toMap(), paused
    )
    fun enqueue(codes: Collection<String>) {
        if (current == null && pending.isEmpty()) {
            requested.clear()
            ready.clear()
            errors.clear()
            paused = false
        }
        for (code in codes.distinct()) {
            requested.add(code)
            if (code != current && code !in pending && code !in ready) {
                errors.remove(code)
                pending.addLast(code)
            }
        }
        publish()
        pump()
    }
    fun pause() {
        if (current != null || pending.isNotEmpty()) {
            paused = true
            publish()
        }
    }
    fun resume() {
        paused = false
        publish()
        pump()
    }
    fun retryFailed() {
        val retry = errors.keys.toList()
        if (retry.isNotEmpty()) enqueue(retry)
    }
    private fun pump() {
        if (current != null) return
        if (pending.isEmpty()) {
            paused = false
            publish()
            return
        }
        if (paused) return
        val code = pending.removeFirst()
        current = code
        val token = ++operation
        publish()
        backend(code) { error ->
            if (token == operation && current == code) {
                if (error == null) ready.add(code) else errors[code] = error
                current = null
                publish()
                pump()
            }
        }
    }
    private fun publish() = changed(snapshot())
}
