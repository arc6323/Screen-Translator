package com.arc6323.screentranslator

import org.junit.Assert.*
import org.junit.Test

class SourceLanguagePolicyTest {
    private fun choice(tag: String, value: Float = 0.99f) = SourceLanguagePolicy.Candidate(tag, value)
    @Test fun targetLanguageIsNeverAnInput() {
        for (target in listOf("ru", "en", "de", "fr", "es", "ja")) {
            assertNull(SourceLanguagePolicy.source("ordinary text", listOf(choice(target)), setOf(target, "en"), target))
        }
    }
    @Test fun cyrillicCannotBeTurnedIntoEnglishEvenWhenMislabelled() {
        for (text in listOf("Домой", "Мои проекты", "Напиши на русском", "Hello Привет", "Обзор Текст песни")) {
            assertNull(SourceLanguagePolicy.source(text, listOf(choice("en")), setOf("en"), "ru"))
        }
    }
    @Test fun unknownIsNeverAssignedToTheOnlySelectedLanguage() {
        assertNull(SourceLanguagePolicy.source("Harmum Ha Ahr", listOf(choice("und")), setOf("en"), "ru"))
        assertNull(SourceLanguagePolicy.source("Ob30P Tekct", emptyList(), setOf("en"), "ru"))
    }
    @Test fun unselectedAndAmbiguousLanguagesAreSkipped() {
        assertNull(SourceLanguagePolicy.source("bonjour tout le monde", listOf(choice("fr")), setOf("en"), "ru"))
        assertNull(SourceLanguagePolicy.source("mixed text", listOf(choice("en", 0.7f), choice("ru", 0.25f)), setOf("en"), "ru"))
        assertNull(SourceLanguagePolicy.source("ambiguous", listOf(choice("en", 0.67f), choice("de", 0.59f)), setOf("en", "de"), "ru"))
    }
    @Test fun verifiedEnglishCanBeTranslatedAndTagsAreNormalized() {
        assertEquals("en", SourceLanguagePolicy.source("Pull requests", listOf(choice("en-US")), setOf("en"), "ru"))
        assertEquals("he", SourceLanguagePolicy.base("iw-IL"))
    }
    @Test fun targetScriptsAndKeyboardCharactersAreSkipped() {
        assertNull(SourceLanguagePolicy.source("Русский", listOf(choice("en")), setOf("en"), "ru"))
        assertNull(SourceLanguagePolicy.source("日本語", listOf(choice("en")), setOf("en"), "ja"))
        assertNull(SourceLanguagePolicy.source("Привіт", listOf(choice("en")), setOf("en"), "uk"))
        assertNull(SourceLanguagePolicy.source("Q", listOf(choice("en")), setOf("en"), "ru"))
        assertNull(SourceLanguagePolicy.source("12345", listOf(choice("en")), setOf("en"), "ru"))
    }
    @Test fun cyrillicGuardDoesNotRejectOtherRecognizedScripts() {
        assertTrue(SourceLanguagePolicy.needsCyrillicCheck("Home", "ru"))
        assertFalse(SourceLanguagePolicy.needsCyrillicCheck("こんにちは", "ru"))
        assertFalse(SourceLanguagePolicy.needsCyrillicCheck("你好", "ru"))
        assertFalse(SourceLanguagePolicy.needsCyrillicCheck("नमस्ते", "ru"))
    }
    @Test fun sharedHanScriptDoesNotDisableChineseToJapaneseTranslation() {
        assertEquals("zh", SourceLanguagePolicy.source("你好世界", listOf(choice("zh")), setOf("zh"), "ja"))
        assertEquals("ja", SourceLanguagePolicy.source("今日は良い日です", listOf(choice("ja")), setOf("ja"), "zh"))
    }
}
