package com.arc6323.screentranslator

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

object LanguageCacheManager {
    private const val PREFS = "translator_prefs"
    private const val KEY_LANGUAGES = "cached_languages"

    data class Language(val code: String, val name: String)

    val languages = listOf(
        Language("en", "English"), Language("de", "Deutsch"), Language("fr", "Français"),
        Language("es", "Español"), Language("it", "Italiano"), Language("pt", "Português"),
        Language("pl", "Polski"), Language("uk", "Українська"), Language("cs", "Čeština"),
        Language("nl", "Nederlands"), Language("sv", "Svenska"), Language("tr", "Türkçe"),
        Language("ro", "Română"), Language("el", "Ελληνικά"), Language("ar", "العربية"),
        Language("he", "עברית"), Language("hi", "हिन्दी"), Language("ja", "日本語"),
        Language("ko", "한국어"), Language("zh", "中文"), Language("vi", "Tiếng Việt"),
        Language("id", "Bahasa Indonesia"), Language("th", "ไทย"), Language("da", "Dansk"),
        Language("no", "Norsk"), Language("fi", "Suomi"), Language("bg", "Български"),
        Language("hu", "Magyar"), Language("sk", "Slovenčina"), Language("ca", "Català"),
        Language("fa", "فارسی")
    )

    private val supportedCodes = languages.map { it.code }.toSet()

    fun targetLanguage(): String {
        val code = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (code in supportedCodes) code else "en"
    }

    fun selected(context: Context): Set<String> = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_LANGUAGES, setOf("en"))?.toSet() ?: setOf("en")

    fun saveSelected(context: Context, codes: Set<String>) {
        val valid = codes.filter { it in supportedCodes }.toSet().ifEmpty { setOf("en") }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_LANGUAGES, valid).apply()
    }

    fun prepareSelected(context: Context, callback: (done: Int, total: Int, failed: Int) -> Unit = { _, _, _ -> }) {
        val required = (selected(context) + targetLanguage()).distinct()
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
        val model = TranslateRemoteModel.Builder(codes[index]).build()
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
                models.filter { it.language !in required }.forEach { manager.deleteDownloadedModel(it) }
            }
    }
}
