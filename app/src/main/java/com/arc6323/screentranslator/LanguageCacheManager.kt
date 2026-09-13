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

    // ML Kit translation models are roughly 30 MB each. ML Kit does not expose
    // byte-level download progress, so the UI reports an estimated speed.
    private const val ESTIMATED_MODEL_MB = 30.0

    data class Language(val code: String, val name: String)
    data class DownloadState(
        val done: Int,
        val total: Int,
        val failed: Int,
        val currentCode: String? = null,
        val currentName: String? = null,
        val elapsedMs: Long = 0L,
        val completedModelCount: Int = 0
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
                // These two are always available by default. English is built into ML Kit.
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
            // Russian + English are the built-in/default pair and cannot be disabled.
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
        if (code == "en") {
            callback(true)
            return
        }
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
        val required = (selected(context) + targetLanguage(context))
            .filter { it in supportedCodes }
            .distinct()

        // English is bundled into the translation runtime. It is not a downloadable
        // remote model, so only real dynamic models are counted as downloads.
        val dynamicCodes = required.filter { it != "en" }
        if (dynamicCodes.isEmpty()) {
            pruneUnused(RemoteModelManager.getInstance(), required.toSet())
            callback(DownloadState(0, 0, 0))
            return
        }

        val manager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().requireWifi().build()
        downloadNext(context, manager, conditions, dynamicCodes, 0, 0, callback)
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
            pruneUnused(manager, (selected(context) + targetLanguage(context)).toSet())
            callback(DownloadState(codes.size, codes.size, failed))
            return
        }

        val code = codes[index]
        val language = TranslateLanguage.fromLanguageTag(code)
        if (language == null) {
            callback(DownloadState(index + 1, codes.size, failed + 1, code))
            downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
            return
        }

        val model = TranslateRemoteModel.Builder(language).build()
        val startedAt = System.currentTimeMillis()
        val name = languages.firstOrNull { it.code == code }?.name ?: code

        manager.isModelDownloaded(model)
            .addOnSuccessListener { alreadyDownloaded ->
                if (alreadyDownloaded) {
                    callback(DownloadState(index + 1, codes.size, failed, code, name, 0L, 0))
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
                    .addOnFailureListener {
                        val elapsed = System.currentTimeMillis() - startedAt
                        callback(DownloadState(index + 1, codes.size, failed + 1, code, name, elapsed, 0))
                        downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
                    }
            }
            .addOnFailureListener {
                callback(DownloadState(index + 1, codes.size, failed + 1, code, name, System.currentTimeMillis() - startedAt, 0))
                downloadNext(context, manager, conditions, codes, index + 1, failed + 1, callback)
            }
    }

    private fun pruneUnused(manager: RemoteModelManager, required: Set<String>) {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                models.filter { it.language !in required && it.language != "en" }
                    .forEach { manager.deleteDownloadedModel(it) }
            }
    }

    fun estimatedSpeedMbPerSec(elapsedMs: Long): Double? {
        if (elapsedMs <= 0L) return null
        return ESTIMATED_MODEL_MB / (elapsedMs / 1000.0)
    }
}
