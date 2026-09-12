package com.arc6323.screentranslator

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

object LanguageCacheManager {
    private const val PREFS = "translator_prefs"
    private const val KEY_LANGUAGES = "cached_languages"
    private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated"

    data class Language(val code: String, val name: String)

    val languages = listOf(
        Language("ru", "Русский"), Language("en", "English"), Language("de", "Deutsch"),
        Language("fr", "Français"), Language("es", "Español"), Language("it", "Italiano"),
        Language("pt", "Português"), Language("pl", "Polski"), Language("uk", "Українська"),
        Language("cs", "Čeština"), Language("nl", "Nederlands"), Language("sv", "Svenska"),
        Language("tr", "Türkçe"), Language("ro", "Română"), Language("el", "Ελληνικά"),
        Language("ar", "العربية"), Language("he", "עברית"), Language("hi", "हिन्दी"),
        Language("ja", "日本語"), Language("ko", "한국어"), Language("zh", "中文"),
        Language("vi", "Tiếng Việt"), Language("id", "Bahasa Indonesia"), Language("th", "ไทย"),
        Language("da", "Dansk"), Language("no", "Norsk"), Language("fi", "Suomi"),
        Language("bg", "Български"), Language("hu", "Magyar"), Language("sk", "Slovenčina"),
        Language("ca", "Català"), Language("fa", "فارسی")
    )

    private val supportedCodes = languages.map { it.code }.toSet()

    /** Returns the phone's system language, not a possible per-app language override. */
    fun targetLanguage(context: Context): String {
        val locale = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            manager?.systemLocales?.get(0)
        } else if (android.os.Build.VERSION.SDK_INT >= 24) {
            android.os.LocaleList.getDefault().get(0)
        } else {
            @Suppress("DEPRECATION") Locale.getDefault()
        }
        val code = locale?.language?.lowercase(Locale.ROOT) ?: "en"
        return if (TranslateLanguage.fromLanguageTag(code) != null) code else "en"
    }

    fun targetLanguageName(context: Context): String {
        val code = targetLanguage(context)
        return languages.firstOrNull { it.code == code }?.name ?: code
    }

    fun selected(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_LANGUAGES, null)?.toMutableSet()
        if (!prefs.getBoolean(KEY_DEFAULTS_MIGRATED, false)) {
            val migrated = (current ?: emptySet()).toMutableSet().apply {
                add("ru")
                add("en")
            }
            prefs.edit()
                .putStringSet(KEY_LANGUAGES, migrated)
                .putBoolean(KEY_DEFAULTS_MIGRATED, true)
                .apply()
            return migrated
        }
        return current ?: setOf("ru", "en")
    }

    fun saveSelected(context: Context, codes: Set<String>) {
        val valid = codes.filter { it in supportedCodes }.toSet().ifEmpty { setOf("ru", "en") }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_LANGUAGES, valid)
            .putBoolean(KEY_DEFAULTS_MIGRATED, true)
            .apply()
    }

    fun prepareSelected(
        context: Context,
        callback: (done: Int, total: Int, failed: Int) -> Unit = { _, _, _ -> }
    ) {
        val required = (selected(context) + targetLanguage(context)).distinct()
        val manager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().requireWifi().build()
        downloadNext(manager, conditions, required, 0, 0, callback)
    }

    private fun downloadNext(
        manager: RemoteModelManager,
        conditions: DownloadConditions,
        codes: List<String>,
        index: Int,
        failed: Int,
        callback: (done: Int, total: Int, failed: Int) -> Unit
    ) {
        if (index >= codes.size) {
            pruneUnused(manager, codes.toSet())
            callback(codes.size, codes.size, failed)
            return
        }

        val code = codes[index]
        // English is built into ML Kit and must not be downloaded/deleted.
        if (code == "en") {
            callback(index + 1, codes.size, failed)
            downloadNext(manager, conditions, codes, index + 1, failed, callback)
            return
        }

        val language = TranslateLanguage.fromLanguageTag(code)
        if (language == null) {
            callback(index + 1, codes.size, failed + 1)
            downloadNext(manager, conditions, codes, index + 1, failed + 1, callback)
            return
        }

        val model = TranslateRemoteModel.Builder(language).build()
        manager.download(model, conditions)
            .addOnSuccessListener {
                callback(index + 1, codes.size, failed)
                downloadNext(manager, conditions, codes, index + 1, failed, callback)
            }
            .addOnFailureListener {
                callback(index + 1, codes.size, failed + 1)
                downloadNext(manager, conditions, codes, index + 1, failed + 1, callback)
            }
    }

    private fun pruneUnused(manager: RemoteModelManager, required: Set<String>) {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                models.filter { it.language !in required && it.language != "en" }
                    .forEach { manager.deleteDownloadedModel(it) }
            }
    }
}
