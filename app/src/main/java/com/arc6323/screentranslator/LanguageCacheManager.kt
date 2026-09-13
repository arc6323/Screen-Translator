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

    private const val ESTIMATED_MODEL_MB = 30.0

    data class Language(val code: String, val name: String)
    data class DownloadState(
        val done: Int,
        val total: Int,
        val failed: Int,
        val currentCode: String? = null,
        val currentName: String? = null,
        val elapsedMs: Long = 0L,
        val completedModelCount: Int = 0,
        val errorMessage: String? = null
    )

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

    fun targetLanguage(context: Context): String {
        val locale = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java)?.systemLocales?.get(0)
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
        val valid = codes.filter { it in supportedCodes }.toMutableSet().apply {
            add("ru")
            add("en")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_LANGUAGES, valid)
            .putBoolean(KEY_DEFAULTS_MIGRATED, true)
            .apply()
    }

    fun isDownloaded(code: String, callback: (Boolean) -> Unit) {
        val language = TranslateLanguage.fromLanguageTag(code)
        if (language == null) {
            callback(false)
            return
        }
        val model = TranslateRemoteModel.Builder(language).build()
        RemoteModelManager.getInstance()
            .isModelDownloaded(model)
            .addOnSuccessListener(callback)
            .addOnFailureListener { callback(false) }
    }

    fun prepareSelected(
        context: Context,
        callback: (DownloadState) -> Unit = {}
    ) {
        // Always keep Russian + English ready. Other selected languages are downloaded on demand.
        val required = (selected(context) + "ru" + "en" + targetLanguage(context))
            .filter { it in supportedCodes }
            .distinct()

        val manager = RemoteModelManager.getInstance()
        // ML Kit translation models are downloaded dynamically. Do not force Wi-Fi here:
        // otherwise the download silently remains pending on networks Android does not classify
        // as Wi-Fi. The UI shows that the models are about 30 MB each.
        val conditions = DownloadConditions.Builder().build()
        downloadNext(context, manager, conditions, required, 0, 0, callback)
    }

    private fun downloadNext(
        context: Context,
        manager: RemoteModelManager,
        conditions: DownloadConditions,
        codes: List<String>,
        index: Int,
        failed: Int,
        callback: (DownloadState) -> Unit
    ) {
        if (index >= codes.size) {
            pruneUnused(manager, (selected(context) + "ru" + "en" + targetLanguage(context)).toSet())
            callback(DownloadState(codes.size, codes.size, failed))
            return
        }

        val code = codes[index]
        val language = TranslateLanguage.fromLanguageTag(code)
        val name = languages.firstOrNull { it.code == code }?.name ?: code
        if (language == null) {
            callback(DownloadState(index + 1, codes.size, failed + 1, code, name, 0L, 0, "Язык не поддерживается ML Kit"))
            downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
            return
        }

        val model = TranslateRemoteModel.Builder(language).build()
        val startedAt = System.currentTimeMillis()

        manager.isModelDownloaded(model)
            .addOnSuccessListener { alreadyDownloaded ->
                if (alreadyDownloaded) {
                    callback(DownloadState(index + 1, codes.size, failed, code, name, 0L, 1))
                    downloadNext(context, manager, conditions, codes, index + 1, failed, callback)
                    return@addOnSuccessListener
                }

                callback(DownloadState(index, codes.size, failed, code, name, 0L, 0))
                manager.download(model, conditions)
                    .addOnSuccessListener {
                        val elapsed = System.currentTimeMillis() - startedAt
                        callback(DownloadState(index + 1, codes.size, failed, code, name, elapsed, 1))
                        downloadNext(context, manager, conditions, codes, index + 1, failed, callback)
                    }
                    .addOnFailureListener { error ->
                        val elapsed = System.currentTimeMillis() - startedAt
                        val message = error.localizedMessage ?: error.javaClass.simpleName
                        callback(DownloadState(index + 1, codes.size, failed + 1, code, name, elapsed, 0, message))
                        downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
                    }
            }
            .addOnFailureListener { error ->
                val elapsed = System.currentTimeMillis() - startedAt
                val message = error.localizedMessage ?: error.javaClass.simpleName
                callback(DownloadState(index + 1, codes.size, failed + 1, code, name, elapsed, 0, message))
                downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
            }
    }

    private fun pruneUnused(manager: RemoteModelManager, required: Set<String>) {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                models.filter { it.language !in required }
                    .forEach { manager.deleteDownloadedModel(it) }
            }
    }

    fun estimatedSpeedMbPerSec(elapsedMs: Long): Double? {
        if (elapsedMs <= 0L) return null
        return ESTIMATED_MODEL_MB / (elapsedMs / 1000.0)
    }
}
