package com.arc6323.screentranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.LinkedHashMap

/** Worker-thread-only, independent Cyrillic check: Latin OCR cannot reliably veto Russian text. */
class CyrillicGuard(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private var api: TessBaseAPI? = null
    private var unavailable = false
    private var imageSet = false
    private val cache = object : LinkedHashMap<String, Boolean>(400, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 400
    }
    private fun engine(): TessBaseAPI? {
        api?.let { return it }
        if (unavailable) return null
        var next: TessBaseAPI? = null
        try {
            val root = File(app.filesDir, "ocr-guard-v1")
            val data = File(root, "tessdata").apply { mkdirs() }
            for ((name, size) in mapOf("eng.traineddata" to 4113088L, "rus.traineddata" to 3861738L)) {
                val file = File(data, name)
                app.assets.open("ocr-guard/$name").use { input ->
                    if (!file.exists() || file.length() != size) {
                        val temporary = File(data, "$name.tmp")
                        temporary.outputStream().use { input.copyTo(it) }
                        check(temporary.length() == size) { "Incomplete OCR model" }
                        check(temporary.renameTo(file)) { "Cannot install OCR model" }
                    }
                }
            }
            next = TessBaseAPI()
            check(next.init(root.absolutePath, "eng+rus", TessBaseAPI.OEM_LSTM_ONLY))
            next.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            next.setVariable("user_defined_dpi", "300")
            api = next
            return next
        } catch (_: Exception) {
            next?.recycle()
            unavailable = true
            return null
        } catch (_: LinkageError) {
            unavailable = true
            return null
        }
    }
    fun beginFrame() { imageSet = false }
    /** null means the check is unavailable, never permission to translate uncertain text. */
    fun allows(bitmap: Bitmap, rect: Rect, key: String): Boolean? {
        cache[key]?.let { return it }
        val tess = engine() ?: return null
        return try {
            if (!imageSet) { tess.setImage(bitmap); imageSet = true }
            val r = Rect(rect).apply {
                inset(-3, -3)
                intersect(0, 0, bitmap.width, bitmap.height)
            }
            tess.setRectangle(r)
            val text = tess.getUTF8Text().orEmpty()
            val allowed = text.count { it.isLetter() } >= 2 &&
                !SourceLanguagePolicy.hasCyrillic(text) && tess.meanConfidence() >= 55
            cache[key] = allowed
            allowed
        } catch (_: RuntimeException) { null }
    }
    fun endFrame() { if (imageSet) api?.clear(); imageSet = false }
    override fun close() { api?.recycle(); api = null; cache.clear() }
}
