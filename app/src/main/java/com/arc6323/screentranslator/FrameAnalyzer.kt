package com.arc6323.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.nl.languageid.LanguageIdentification
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

class FrameAnalyzer : AutoCloseable {
    data class Result(val items: List<OverlayView.Item>, val elapsedMs: Long, val unchanged: Boolean, val error: String? = null)
    private data class Block(val text: String, val rect: Rect, val background: Int)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ocr-preparation").apply { start() }
    private val worker = Handler(thread.looper)
    private val recognizers = mutableMapOf<String, TextRecognizer>()
    private val languageId = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val cache = object : LinkedHashMap<String, String>(300, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    private var closed = false
    private var ocrBusy = false
    val isBusy: Boolean get() = ocrBusy
    private var previousSignature: String? = null

    /** Takes ownership of bitmap, including early returns and failed OCR. */
    fun analyze(
        bitmap: Bitmap, selected: Set<String>, target: String,
        current: () -> Boolean, complete: (Result) -> Unit
    ) {
        val started = SystemClock.elapsedRealtime()
        if (closed || ocrBusy) {
            bitmap.recycle()
            complete(Result(emptyList(), 0, false, "Распознавание ещё выполняется"))
            return
        }
        ocrBusy = true
        val scripts = mutableListOf("latin")
        if ("zh" in selected) scripts.add("zh")
        if ("hi" in selected) scripts.add("hi")
        if ("ja" in selected) scripts.add("ja")
        if ("ko" in selected) scripts.add("ko")
        val recognized = mutableListOf<Text.TextBlock>()
        var remaining = scripts.size
        var failures = 0
        fun finishRecognizer() {
            remaining--
            if (remaining != 0) return
            worker.post {
                val blocks = try {
                    recognized.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                        .mapNotNull { block ->
                            val rect = block.boundingBox ?: return@mapNotNull null
                            val text = block.text.trim()
                            if (rect.width() <= 0 || rect.height() <= 0 || text.none { it.isLetter() }) null
                            else Block(text, Rect(rect), sampleBackground(bitmap, rect))
                        }.fold(mutableListOf<Block>()) { list, block ->
                            if (list.none { overlaps(it.rect, block.rect) }) list.add(block)
                            list
                        }
                } catch (_: RuntimeException) {
                    emptyList()
                } finally {
                    bitmap.recycle()
                }
                main.post {
                    ocrBusy = false
                    if (closed || !current()) return@post
                    if (failures == scripts.size) {
                        complete(Result(emptyList(), SystemClock.elapsedRealtime() - started, false, "Ошибка распознавания текста"))
                    } else identify(blocks, selected, target, started, current, complete)
                }
            }
        }
        scripts.forEach { script ->
            try {
                recognizer(script).process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { recognized.addAll(it.textBlocks) }
                    .addOnFailureListener { failures++ }
                    .addOnCompleteListener { finishRecognizer() }
            } catch (_: RuntimeException) {
                failures++
                finishRecognizer()
            }
        }
    }

    private fun identify(
        blocks: List<Block>, selected: Set<String>, target: String, started: Long,
        current: () -> Boolean, complete: (Result) -> Unit
    ) {
        if (blocks.isEmpty()) {
            previousSignature = null
            complete(Result(emptyList(), SystemClock.elapsedRealtime() - started, false))
            return
        }
        // Bound language-ID work separately from the budget of translatable blocks.
        val candidates = blocks.take(80)
        val sources = Array(candidates.size) { "und" }
        var remaining = candidates.size
        candidates.forEachIndexed { index, block ->
            languageId.identifyLanguage(block.text)
                .addOnSuccessListener { sources[index] = it }
                .addOnCompleteListener {
                    remaining--
                    if (remaining == 0 && !closed && current()) {
                        val fallback = selected.filter { it != target && LanguageCacheManager.supportsScreenOcr(it) }.singleOrNull()
                        val matches = candidates.indices.mapNotNull { position ->
                            val source = sources[position].let { if (it == "und") fallback ?: it else it }
                            if (source in selected && source != target && TranslateLanguage.fromLanguageTag(source) != null) {
                                candidates[position] to source
                            } else null
                        }.take(24)
                        translate(matches, target, started, current, complete)
                    }
                }
        }
    }

    private fun translate(
        blocks: List<Pair<Block, String>>, target: String, started: Long,
        current: () -> Boolean, complete: (Result) -> Unit
    ) {
        val signature = target + blocks.joinToString { (block, source) -> "$source|${block.rect}|${block.text}" }
        val unchanged = signature == previousSignature
        val items = mutableListOf<OverlayView.Item>()
        var remaining = blocks.size
        var failures = 0
        fun finish() {
            if (closed || !current()) return
            if (failures == 0) previousSignature = signature
            complete(Result(items.sortedWith(compareBy({ it.rect.top }, { it.rect.left })),
                SystemClock.elapsedRealtime() - started, unchanged,
                if (failures > 0) "Не удалось перевести блоков: $failures. Проверьте языковые модели." else null))
        }
        if (blocks.isEmpty()) { finish(); return }
        fun result(block: Block, text: String?) {
            if (text == null) failures++
            else if (text.isNotBlank() && text.trim() != block.text.trim()) {
                items.add(OverlayView.Item(block.rect, text.trim(), block.background))
            }
            remaining--
            if (remaining == 0) finish()
        }
        blocks.forEach { (block, source) ->
            val key = "$source|$target|${block.text}"
            val cached = cache[key]
            if (cached != null) result(block, cached)
            else try {
                val pair = "$source->$target"
                val translator = translators.getOrPut(pair) {
                    Translation.getClient(TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.fromLanguageTag(source)!!)
                        .setTargetLanguage(TranslateLanguage.fromLanguageTag(target)!!).build())
                }
                // Model availability is checked before capture; no network download inside a frame.
                translator.translate(block.text)
                    .addOnSuccessListener {
                        if (!closed && current()) cache[key] = it
                        result(block, it)
                    }
                    .addOnFailureListener { result(block, null) }
            } catch (_: RuntimeException) { result(block, null) }
        }
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
        recognizers.values.forEach { it.close() }
        translators.values.forEach { it.close() }
        languageId.close()
        cache.clear()
        // Do not stop the worker while an OCR completion still owns a Bitmap.
        if (ocrBusy) main.postDelayed({ finishClosingWorker() }, 200)
        else thread.quitSafely()
    }

    private fun finishClosingWorker() {
        if (ocrBusy) main.postDelayed({ finishClosingWorker() }, 200)
        else thread.quitSafely()
    }
}
