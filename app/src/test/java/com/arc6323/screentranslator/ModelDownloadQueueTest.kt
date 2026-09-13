package com.arc6323.screentranslator

import org.junit.Assert.*
import org.junit.Test

class ModelDownloadQueueTest {
    private class Backend {
        val started = mutableListOf<String>()
        val callbacks = mutableListOf<(String?) -> Unit>()
        fun start(code: String, done: (String?) -> Unit) {
            started.add(code)
            callbacks.add(done)
        }
        fun finish(error: String? = null) = callbacks.last()(error)
    }

    @Test fun pauseFinishesCurrentAndResumePreservesRemainingQueue() {
        val backend = Backend()
        val queue = ModelDownloadQueue(backend::start)
        queue.enqueue(listOf("de", "fr"))
        queue.pause()
        backend.finish()
        assertEquals(listOf("de"), backend.started)
        assertTrue(queue.snapshot().paused)
        assertEquals(listOf("fr"), queue.snapshot().queued)
        queue.resume()
        assertEquals(listOf("de", "fr"), backend.started)
        backend.finish()
        assertTrue(queue.snapshot().successful)
    }

    @Test fun repeatedRequestsDoNotStartConcurrentOrDuplicateDownloads() {
        val backend = Backend()
        val queue = ModelDownloadQueue(backend::start)
        queue.enqueue(listOf("ru", "de"))
        queue.enqueue(listOf("de", "fr", "ru"))
        assertEquals(listOf("ru"), backend.started)
        backend.finish()
        backend.finish()
        backend.finish()
        assertEquals(listOf("ru", "de", "fr"), backend.started)
        assertEquals(setOf("ru", "de", "fr"), queue.snapshot().ready)
    }

    @Test fun aNewRequestDoesNotCancelPause() {
        val backend = Backend()
        val queue = ModelDownloadQueue(backend::start)
        queue.enqueue(listOf("de", "fr"))
        queue.pause()
        queue.enqueue(listOf("ja"))
        backend.finish()
        assertTrue(queue.snapshot().paused)
        assertEquals(listOf("fr", "ja"), queue.snapshot().queued)
        assertEquals(listOf("de"), backend.started)
    }

    @Test fun failureRemainsVisibleAndCannotCountAsSuccess() {
        val backend = Backend()
        val queue = ModelDownloadQueue(backend::start)
        queue.enqueue(listOf("ru", "de"))
        backend.finish("Network unavailable")
        backend.finish()
        assertTrue(queue.snapshot().finished)
        assertFalse(queue.snapshot().successful)
        assertEquals(setOf("de"), queue.snapshot().ready)
        assertEquals(mapOf("ru" to "Network unavailable"), queue.snapshot().errors)
        queue.retryFailed()
        assertEquals(listOf("ru", "de", "ru"), backend.started)
        backend.finish()
        assertTrue(queue.snapshot().successful)
    }

    @Test fun lateDuplicateCompletionCannotFinishANewModel() {
        val backend = Backend()
        val queue = ModelDownloadQueue(backend::start)
        queue.enqueue(listOf("de", "fr"))
        val oldCallback = backend.callbacks.first()
        backend.finish()
        oldCallback("late error")
        assertEquals("fr", queue.snapshot().current)
        assertTrue(queue.snapshot().errors.isEmpty())
        backend.finish()
        assertTrue(queue.snapshot().successful)
    }

    @Test fun builtInSynchronousCompletionsDoNotLoseNextDownload() {
        val started = mutableListOf<String>()
        var finishRussian: ((String?) -> Unit)? = null
        val queue = ModelDownloadQueue({ code, done ->
            started.add(code)
            if (code == "en") done(null) else finishRussian = done
        })
        queue.enqueue(listOf("en", "ru"))
        assertEquals(listOf("en", "ru"), started)
        assertEquals("ru", queue.snapshot().current)
        finishRussian!!(null)
        assertTrue(queue.snapshot().successful)
    }
}
