package com.arc6323.screentranslator

import java.util.Locale

/** Fail closed for unknown/ambiguous text; selection is never evidence of its language. */
object SourceLanguagePolicy {
    data class Candidate(val language: String, val confidence: Float)
    fun base(tag: String) = tag.lowercase(Locale.ROOT).substringBefore('-').let {
        when (it) { "iw" -> "he"; "nb", "nn" -> "no"; else -> it }
    }
    fun isCyrillicTarget(target: String) = base(target) in setOf("ru", "uk", "bg")
    fun hasCyrillic(text: String) = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
    fun containsTargetScript(text: String, target: String): Boolean {
        val script = when (base(target)) {
            "ru", "uk", "bg" -> Character.UnicodeScript.CYRILLIC
            "el" -> Character.UnicodeScript.GREEK
            "ar", "fa" -> Character.UnicodeScript.ARABIC
            "he" -> Character.UnicodeScript.HEBREW
            "hi" -> Character.UnicodeScript.DEVANAGARI
            "th" -> Character.UnicodeScript.THAI
            "ko" -> Character.UnicodeScript.HANGUL
            else -> null
        }
        if (script != null && text.any { Character.UnicodeScript.of(it.code) == script }) return true
        if (base(target) in setOf("zh", "ja")) return text.any {
            Character.UnicodeScript.of(it.code) in setOf(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        }
        return false
    }
    fun source(text: String, choices: List<Candidate>, selected: Set<String>, target: String): String? {
        val destination = base(target)
        if (text.count { it.isLetter() } < 2 || containsTargetScript(text, destination)) return null
        val ranked = choices.filter { it.confidence.isFinite() }.sortedByDescending { it.confidence }
        // A plausible target-language interpretation always wins over translating it again.
        if (ranked.any { base(it.language) == destination && it.confidence >= 0.20f }) return null
        val best = ranked.firstOrNull() ?: return null
        val language = base(best.language)
        if (language == "und" || language == destination || language !in selected.map(::base)) return null
        if (best.confidence < 0.65f) return null
        if (ranked.drop(1).any { base(it.language) != language && best.confidence - it.confidence < 0.15f }) return null
        return language
    }
}
