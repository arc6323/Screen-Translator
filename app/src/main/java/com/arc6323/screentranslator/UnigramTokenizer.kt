package com.arc6323.screentranslator

import java.io.InputStream
import java.nio.ByteBuffer

/** Pinned OPUS tokenizer. Indexed trie avoids building thousands of maps on a phone. */
class UnigramTokenizer(input: InputStream) {
    private val pieces: List<String>
    private val unknownScore: Double
    private val ids: IntArray
    private val scores: FloatArray
    private val first: IntArray
    private val counts: IntArray
    private val chars: CharArray
    private val targets: IntArray
    init {
        val bytes = input.use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes)
        check(buffer.int == 0x554E4931) { "Unknown tokenizer format" }
        val count = buffer.int
        check(count == 62518)
        unknownScore = buffer.double
        pieces = List(count) {
            val length = buffer.int
            val text = String(bytes, buffer.position(), length, Charsets.UTF_8)
            buffer.position(buffer.position() + length)
            text
        }
        val nodes = buffer.int
        ids = IntArray(nodes); scores = FloatArray(nodes); first = IntArray(nodes); counts = IntArray(nodes)
        for (i in 0 until nodes) { ids[i] = buffer.int; scores[i] = buffer.float; first[i] = buffer.int; counts[i] = buffer.int }
        val edges = buffer.int
        chars = CharArray(edges); targets = IntArray(edges)
        for (i in 0 until edges) { chars[i] = buffer.short.toInt().and(65535).toChar(); targets[i] = buffer.int }
        check(!buffer.hasRemaining())
    }
    private fun child(node: Int, char: Char): Int {
        var low = first[node]
        var high = low + counts[node] - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                chars[middle] < char -> low = middle + 1
                chars[middle] > char -> high = middle - 1
                else -> return targets[middle]
            }
        }
        return -1
    }
    fun encode(text: String): LongArray {
        val result = mutableListOf<Long>()
        for (word in text.trim().split(Regex("[\\s\\p{Z}]+"))) {
            if (word.isEmpty()) continue
            val s = "▁$word"
            val score = DoubleArray(s.length + 1) { Double.NEGATIVE_INFINITY }
            val previous = IntArray(s.length + 1)
            val tokenIds = IntArray(s.length + 1)
            score[0] = 0.0
            var start = 0
            while (start < s.length) {
                if (score[start].isFinite()) {
                    var node = 0
                    var end = start
                    var hasSingle = false
                    val singleEnd = start + Character.charCount(s.codePointAt(start))
                    while (end < s.length) {
                        node = child(node, s[end])
                        if (node < 0) break
                        end++
                        if (ids[node] >= 0) {
                            if (end == singleEnd) hasSingle = true
                            val nextScore = score[start] + scores[node]
                            if (nextScore > score[end]) {
                                score[end] = nextScore; previous[end] = start; tokenIds[end] = ids[node]
                            }
                        }
                    }
                    if (!hasSingle && score[start] + unknownScore > score[singleEnd]) {
                        score[singleEnd] = score[start] + unknownScore; previous[singleEnd] = start; tokenIds[singleEnd] = 1
                    }
                }
                start += Character.charCount(s.codePointAt(start))
            }
            var end = s.length
            val reversed = mutableListOf<Long>()
            while (end > 0) { reversed.add(tokenIds[end].toLong()); end = previous[end] }
            result.addAll(reversed.asReversed())
        }
        // Don't silently translate a truncated source sentence.
        require(result.size < 256) { "Line exceeds offline translation limit" }
        result.add(0)
        return result.toLongArray()
    }
    fun decode(ids: List<Int>) = ids.filter { it != 0 && it != 62517 }
        .joinToString("") { pieces[it] }.replace('▁', ' ').trim()
}
