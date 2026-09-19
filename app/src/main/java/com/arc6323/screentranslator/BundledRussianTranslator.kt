package com.arc6323.screentranslator

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors

/** Apache-2.0 OPUS-MT English -> Russian. All model bytes come from this APK. */
class BundledRussianTranslator(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var tokenizer: UnigramTokenizer? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private val environment = OrtEnvironment.getEnvironment()

    fun translate(texts: List<String>, current: () -> Boolean, done: (List<String>?) -> Unit) {
        if (closed) return
        worker.execute {
            if (closed || !current()) return@execute
            val result = try { prepare(); run(texts, current) } catch (_: Exception) { null }
            main.post { if (!closed && current()) done(result) }
        }
    }
    private fun prepare() {
        if (encoder != null && decoder != null) return
        tokenizer = UnigramTokenizer(app.assets.open("russian/tokenizer.json"))
        fun model(name: String, size: Long): String {
            val directory = File(app.noBackupFilesDir, "opus-en-ru-e050376").apply { mkdirs() }
            val file = File(directory, name)
            if (file.length() != size) {
                val temp = File(directory, "$name.tmp")
                app.assets.open("russian/$name").use { source -> temp.outputStream().use { source.copyTo(it) } }
                check(temp.length() == size && temp.renameTo(file)) { "Bundled model copy failed" }
            }
            return file.absolutePath
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            try {
                encoder = environment.createSession(model("encoder_model_quantized.onnx", 51628446), options)
                decoder = environment.createSession(model("decoder_model_merged_quantized.onnx", 58931576), options)
            } catch (e: Exception) {
                encoder?.close(); encoder = null; decoder?.close(); decoder = null
                throw e
            }
        }
    }
    private fun run(texts: List<String>, current: () -> Boolean): List<String>? {
        if (texts.isEmpty()) return emptyList()
        require(texts.size <= 4)
        val tokenizer = checkNotNull(tokenizer)
        val ids = texts.map { tokenizer.encode(it) }
        val batch = ids.size
        val length = ids.maxOf { it.size }
        val input = LongArray(batch * length) { 62517 }
        val attention = LongArray(input.size)
        ids.forEachIndexed { row, tokens -> tokens.forEachIndexed { column, token ->
            input[row * length + column] = token; attention[row * length + column] = 1
        } }
        fun longTensor(values: LongArray, width: Int) = OnnxTensor.createTensor(environment,
            LongBuffer.wrap(values), longArrayOf(batch.toLong(), width.toLong()))
        val owned = mutableListOf<AutoCloseable>()
        var first: OrtSession.Result? = null
        var last: OrtSession.Result? = null
        try {
            val source = longTensor(input, length).also { owned.add(it) }
            val mask = longTensor(attention, length).also { owned.add(it) }
            val encoded = checkNotNull(encoder).run(mapOf("input_ids" to source, "attention_mask" to mask))
                .also { owned.add(it) }
            val empty = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(batch.toLong(), 8, 0, 64))
                .also { owned.add(it) }
            val noCache = OnnxTensor.createTensor(environment, booleanArrayOf(false)).also { owned.add(it) }
            val useCache = OnnxTensor.createTensor(environment, booleanArrayOf(true)).also { owned.add(it) }
            val feed = HashMap<String, OnnxTensorLike>()
            feed["encoder_hidden_states"] = encoded.get("last_hidden_state").get() as OnnxTensor
            feed["encoder_attention_mask"] = mask
            val cacheNames = checkNotNull(decoder).inputNames.filter { it.startsWith("past_key_values.") }
            for (key in cacheNames) feed[key] = empty
            var next = LongArray(batch) { 62517 }
            val finished = BooleanArray(batch)
            val output = List(batch) { mutableListOf<Int>() }
            // Bound decoding latency and discard incomplete text rather than show a false translation.
            for (step in 0 until 96) {
                if (closed || !current()) return null
                feed["use_cache_branch"] = if (step == 0) noCache else useCache
                val result = longTensor(next, 1).use { token ->
                    feed["input_ids"] = token
                    checkNotNull(decoder).run(feed)
                }
                if (first == null) first = result
                val logits = (result.get("logits").get() as OnnxTensor).floatBuffer.get()
                next = LongArray(batch)
                for (row in 0 until batch) {
                    var best = 0
                    var value = Float.NEGATIVE_INFINITY
                    for (id in 0 until 62517) {
                        val score = logits.get(row * 62518 + id)
                        if (score > value) { value = score; best = id }
                    }
                    if (!finished[row]) {
                        if (best == 0) finished[row] = true else output[row].add(best)
                    }
                    next[row] = if (finished[row]) 0 else best.toLong()
                }
                for (key in cacheNames) {
                    val owner = if (".encoder." in key) checkNotNull(first) else result
                    feed[key] = owner.get(key.replace("past_key_values.", "present.")).get() as OnnxTensor
                }
                if (last !== first) last?.close()
                last = result
                if (finished.all { it }) return output.map { tokenizer.decode(it) }
            }
            return output.mapIndexed { index, tokens -> if (finished[index]) tokenizer.decode(tokens) else "" }
        } finally {
            if (last !== first) last?.close()
            first?.close()
            owned.asReversed().forEach { it.close() }
        }
    }
    override fun close() {
        closed = true
        worker.execute { encoder?.close(); decoder?.close(); tokenizer = null }
        worker.shutdown()
    }
}
