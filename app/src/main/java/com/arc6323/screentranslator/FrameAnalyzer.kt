package com.arc6323.screentranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashMap

class FrameAnalyzer(context: Context) : AutoCloseable {
    data class Result(val items: List<OverlayView.Item>, val elapsedMs: Long, val unchanged: Boolean, val error: String? = null)
    private data class Block(val text: String, val rect: Rect, val background: Int)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ocr-preparation").apply { start() }
    private val worker = Handler(thread.looper)
    private val cyrillic = CyrillicGuard(context)
    private val recognizers = mutableMapOf<String, TextRecognizer>()
    private val languageId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.20f).build())
    private val translators = mutableMapOf<String, Translator>()
    private val textCache = lru<String, String>(500)
    private val languageCache = lru<String, List<SourceLanguagePolicy.Candidate>>(500)
    private val inFlight = mutableMapOf<String, Task<String>>()
    @Volatile private var closed = false
    @Volatile private var ocrBusy = false
    val isBusy: Boolean get() = ocrBusy
    private var previousVisual: String? = null
    private var previousResult: Result? = null

    private fun <K, V> lru(limit: Int) = object : LinkedHashMap<K, V>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
    }

    /** Takes ownership of bitmap. Partial results do not complete the frame. */
    fun analyze(
        bitmap: Bitmap, selected: Set<String>, target: String, bounds: Rect,
        current: () -> Boolean, partial: (List<OverlayView.Item>) -> Unit,
        complete: (Result) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        if (closed || ocrBusy) {
            bitmap.recycle()
            complete(Result(emptyList(), 0, false, "Распознавание ещё выполняется"))
            return
        }
        ocrBusy = true
        worker.post {
            val visual = try {
                target + "|" + selected.sorted().joinToString() + "|" + pixelKey(bitmap, bounds)
            } catch (_: RuntimeException) { null }
            main.post {
                if (closed || !current()) { bitmap.recycle(); ocrBusy = false; return@post }
                val previous = previousResult
                if (visual != null && visual == previousVisual && previous != null) {
                    bitmap.recycle()
                    ocrBusy = false
                    complete(previous.copy(elapsedMs = SystemClock.elapsedRealtime() - started, unchanged = true))
                } else recognize(bitmap, selected, target, bounds, visual, started, current, partial, complete)
            }
        }
    }

    private fun recognize(
        bitmap: Bitmap, selected: Set<String>, target: String, bounds: Rect, visual: String?,
        started: Long, current: () -> Boolean, partial: (List<OverlayView.Item>) -> Unit,
        complete: (Result) -> Unit
    ) {
        val scripts = mutableListOf("latin")
        for (script in listOf("zh", "hi", "ja", "ko")) if (script in selected || script == target) scripts.add(script)
        val lines = mutableListOf<Text.Line>()
        val batchItems = mutableMapOf<Int, List<OverlayView.Item>>()
        var pendingBatches = 0
        var preparationDone = false
        var frameError: String? = null
        var remaining = scripts.size
        var failures = 0
        fun allItems() = batchItems.values.flatten().sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        fun finishStreaming() {
            if (!preparationDone || pendingBatches != 0 || closed || !current()) return
            val result = Result(allItems(), SystemClock.elapsedRealtime() - started, false, frameError)
            if (frameError == null && visual != null) { previousVisual = visual; previousResult = result }
            complete(result)
        }
        fun submitBatch(id: Int, blocks: List<Block>) {
            if (blocks.isEmpty()) return
            main.post {
                if (closed || !current()) return@post
                pendingBatches++
                identify(blocks, selected, target, null, started, null, current,
                    partial = { items -> batchItems[id] = items; partial(allItems()) },
                    complete = { result ->
                        batchItems[id] = result.items
                        if (result.error != null) frameError = result.error
                        pendingBatches--
                        partial(allItems())
                        finishStreaming()
                    })
            }
        }
        fun finishRecognizer() {
            remaining--
            if (remaining != 0) return
            worker.post {
                var guardUnavailable = false
                var preparationFailed = false
                cyrillic.beginFrame()
                try {
                    val ordered = lines.filter { line ->
                        val rect = line.boundingBox
                        rect != null && bounds.contains(rect) && rect.width() > 0 && rect.height() > 0 &&
                            line.text.count { it.isLetter() } >= 2 && line.confidence >= 0.55f
                    }.sortedWith(compareBy<Text.Line>(
                        { !SourceLanguagePolicy.containsTargetScript(it.text, target) },
                        { it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    val unique = mutableListOf<Text.Line>()
                    ordered.forEach { line ->
                        if (unique.none { overlaps(it.boundingBox!!, line.boundingBox!!) }) unique.add(line)
                    }
                    var batch = mutableListOf<Block>()
                    var batchId = 0
                    unique.sortedBy { it.boundingBox!!.top }.take(64).forEachIndexed { index, line ->
                        if (closed || !current()) return@forEachIndexed
                        val rect = line.boundingBox!!
                        val text = line.text.trim()
                        var allowed = !SourceLanguagePolicy.containsTargetScript(text, target)
                        if (allowed && SourceLanguagePolicy.isCyrillicTarget(target)) {
                            val checked = cyrillic.allows(bitmap, rect, text + "|" + pixelKey(bitmap, rect))
                            if (checked == null) guardUnavailable = true
                            allowed = checked == true
                        }
                        if (allowed) batch.add(Block(text, Rect(rect), sampleBackground(bitmap, rect)))
                        if (index % 4 == 3) {
                            submitBatch(batchId++, batch.toList())
                            batch = mutableListOf()
                        }
                    }
                    submitBatch(batchId, batch.toList())
                } catch (_: RuntimeException) {
                    preparationFailed = true
                } finally {
                    cyrillic.endFrame()
                    bitmap.recycle()
                }
                main.post {
                    ocrBusy = false
                    if (closed || !current()) return@post
                    when {
                        failures == scripts.size || preparationFailed -> frameError = "Не удалось распознать текст"
                        guardUnavailable -> frameError = "Не удалось проверить кириллицу. Перезапустите перевод."
                    }
                    preparationDone = true
                    finishStreaming()
                }
            }
        }
        scripts.forEach { script ->
            try {
                recognizer(script).process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> lines.addAll(result.textBlocks.flatMap { it.lines }) }
                    .addOnFailureListener { failures++ }
                    .addOnCompleteListener { finishRecognizer() }
            } catch (_: RuntimeException) { failures++; finishRecognizer() }
        }
    }

    private fun identify(
        blocks: List<Block>, selected: Set<String>, target: String, visual: String?,
        started: Long, error: String?, current: () -> Boolean,
        partial: (List<OverlayView.Item>) -> Unit, complete: (Result) -> Unit
    ) {
        val choices = mutableMapOf<String, List<SourceLanguagePolicy.Candidate>>()
        val texts = blocks.map { it.text }.distinct()
        var remaining = texts.size
        fun ready() {
            if (closed || !current()) return
            val matches = blocks.mapNotNull { block ->
                val source = SourceLanguagePolicy.source(block.text, choices[block.text].orEmpty(), selected, target)
                if (source == null || TranslateLanguage.fromLanguageTag(source) == null) null else block to source
            }
            translate(matches, target, visual, started, error, current, partial, complete)
        }
        if (texts.isEmpty()) { ready(); return }
        texts.forEach { text ->
            val cached = languageCache[text]
            if (cached != null) {
                choices[text] = cached
                remaining--
                if (remaining == 0) ready()
            } else languageId.identifyPossibleLanguages(text)
                .addOnSuccessListener { identified ->
                    val result = identified.map { SourceLanguagePolicy.Candidate(it.languageTag, it.confidence) }
                    if (!closed) languageCache[text] = result
                    choices[text] = result
                }
                .addOnCompleteListener { remaining--; if (remaining == 0) ready() }
        }
    }

    private fun translate(
        blocks: List<Pair<Block, String>>, target: String, visual: String?, started: Long,
        priorError: String?, current: () -> Boolean, partial: (List<OverlayView.Item>) -> Unit,
        complete: (Result) -> Unit
    ) {
        val items = mutableListOf<OverlayView.Item>()
        var remaining = blocks.size
        var failures = 0
        var partialScheduled = false
        fun sorted() = items.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        fun publish() {
            if (partialScheduled || closed || !current()) return
            partialScheduled = true
            main.postDelayed({
                partialScheduled = false
                if (!closed && current()) partial(sorted())
            }, 16)
        }
        fun finish() {
            if (closed || !current()) return
            val error = priorError ?: if (failures > 0) "Часть строк не переведена. Проверьте модели." else null
            val result = Result(sorted(), SystemClock.elapsedRealtime() - started, false, error)
            if (error == null && visual != null) { previousVisual = visual; previousResult = result }
            complete(result)
        }
        if (blocks.isEmpty()) { finish(); return }
        fun result(block: Block, text: String?) {
            if (text == null) failures++
            else if (text.isNotBlank() && text.trim() != block.text.trim()) {
                items.add(OverlayView.Item(block.rect, text.trim(), block.background))
                publish()
            }
            remaining--
            if (remaining == 0) finish()
        }
        blocks.forEach { (block, source) ->
            val key = source + "|" + target + "|" + block.text
            val cached = textCache[key]
            if (cached != null) result(block, cached)
            else try {
                val task = inFlight.getOrPut(key) {
                    val translator = translators.getOrPut(source + "->" + target) {
                        Translation.getClient(TranslatorOptions.Builder()
                            .setSourceLanguage(TranslateLanguage.fromLanguageTag(source)!!)
                            .setTargetLanguage(TranslateLanguage.fromLanguageTag(target)!!).build())
                    }
                    translator.translate(block.text).also { task ->
                        // Keep successful work even if a newer frame has superseded its screen coordinates.
                        task.addOnSuccessListener { if (!closed) textCache[key] = it }
                        task.addOnCompleteListener { inFlight.remove(key) }
                    }
                }
                task.addOnSuccessListener { result(block, it) }
                    .addOnFailureListener { result(block, null) }
            } catch (_: RuntimeException) { result(block, null) }
        }
    }

    /** Stable content key independent of screen position, to reuse checks while scrolling. */
    private fun pixelKey(bitmap: Bitmap, bounds: Rect): String {
        val r = Rect(bounds)
        if (!r.intersect(0, 0, bitmap.width, bitmap.height)) return "empty"
        val row = IntArray(r.width())
        var hash = -3750763034362895579L
        var second = 1125899906842597L
        var y = r.top
        while (y < r.bottom) {
            bitmap.getPixels(row, 0, row.size, r.left, y, row.size, 1)
            for (x in row.indices step 2) {
                hash = (hash xor row[x].toLong()) * 1099511628211L
                second = second * 31 + row[x]
            }
            y += 2
        }
        return r.width().toString() + "x" + r.height() + ":" + hash + ":" + second
    }

    private fun recognizer(script: String) = recognizers.getOrPut(script) {
        when (script) {
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        val intersection = Rect(a)
        if (!intersection.intersect(b)) return false
        return intersection.width().toLong() * intersection.height() >
            minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height()) * 0.72
    }

    private fun sampleBackground(bitmap: Bitmap, rect: Rect): Int {
        val points = mutableListOf<Int>()
        val left = (rect.left - 3).coerceIn(0, bitmap.width - 1)
        val right = (rect.right + 3).coerceIn(0, bitmap.width - 1)
        val top = (rect.top - 3).coerceIn(0, bitmap.height - 1)
        val bottom = (rect.bottom + 3).coerceIn(0, bitmap.height - 1)
        for (i in 0..10) {
            val x = left + (right - left) * i / 10
            val y = top + (bottom - top) * i / 10
            points.add(bitmap.getPixel(x, top))
            points.add(bitmap.getPixel(x, bottom))
            points.add(bitmap.getPixel(left, y))
            points.add(bitmap.getPixel(right, y))
        }
        fun median(channel: (Int) -> Int) = points.map(channel).sorted()[points.size / 2]
        return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    }

    override fun close() {
        closed = true
        fun release() {
            if (ocrBusy) { main.postDelayed({ closeWhenIdle() }, 100); return }
            worker.post { cyrillic.close(); thread.quitSafely() }
        }
        recognizers.values.forEach { it.close() }
        translators.values.forEach { it.close() }
        languageId.close()
        textCache.clear()
        languageCache.clear()
        inFlight.clear()
        release()
    }
    private fun closeWhenIdle() {
        if (ocrBusy) main.postDelayed({ closeWhenIdle() }, 100)
        else worker.post { cyrillic.close(); thread.quitSafely() }
    }
}
