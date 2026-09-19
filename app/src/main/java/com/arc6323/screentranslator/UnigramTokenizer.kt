package com.arc6323.screentranslator

import org.json.JSONObject
import java.io.InputStream

/** Pinned OPUS tokenizer: whitespace + metaspace + unigram, EOS=0, unknown=1. */
class UnigramTokenizer(input: InputStream) {
    private class Node {
        val children = HashMap<Char, Node>()
        var id = -1
        var score = 0.0
    }
    private val root = Node()
    private val pieces: List<String>
    private val unknownScore: Double
    init {
        val json = input.bufferedReader().use { JSONObject(it.readText()) }
        val vocab = json.getJSONObject("model").getJSONArray("vocab")
        var minimum = 0.0
        pieces = List(vocab.length()) { index ->
            val entry = vocab.getJSONArray(index)
            val piece = entry.getString(0)
            val score = entry.getDouble(1)
            minimum = minOf(minimum, score)
            if (index !in setOf(0, 1, 62517)) {
                var node = root
                for (c in piece) node = node.children.getOrPut(c) { Node() }
                node.id = index; node.score = score
            }
            piece
        }
        unknownScore = minimum - 10
    }
    fun encode(text: String): LongArray {
        val result = mutableListOf<Long>()
        for (word in text.trim().split(Regex("[\\s\\p{Z}]+"))) {
            if (word.isEmpty()) continue
            val s = "▁$word"
            val score = DoubleArray(s.length + 1) { Double.NEGATIVE_INFINITY }
            val previous = IntArray(s.length + 1)
            val ids = IntArray(s.length + 1)
            score[0] = 0.0
            var start = 0
            while (start < s.length) {
                if (score[start].isFinite()) {
                    var node = root
                    var end = start
                    var hasSingle = false
                    val singleEnd = start + Character.charCount(s.codePointAt(start))
                    while (end < s.length) {
                        node = node.children[s[end]] ?: break
                        end++
                        if (node.id >= 0) {
                            if (end == singleEnd) hasSingle = true
                            val nextScore = score[start] + node.score
                            if (nextScore > score[end]) {
                                score[end] = nextScore; previous[end] = start; ids[end] = node.id
                            }
                        }
                    }
                    if (!hasSingle && score[start] + unknownScore > score[singleEnd]) {
                        score[singleEnd] = score[start] + unknownScore; previous[singleEnd] = start; ids[singleEnd] = 1
                    }
                }
                start += Character.charCount(s.codePointAt(start))
            }
            var end = s.length
            val reversed = mutableListOf<Long>()
            while (end > 0) { reversed.add(ids[end].toLong()); end = previous[end] }
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
