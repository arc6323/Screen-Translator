package com.arc6323.screentranslator

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

object LanguageCacheManager {
    data class Language(val code: String, val name: String)

    val languages = listOf(
        Language("en", "English"),
        Language("de", "Deutsch"),
        Language("fr", "Français"),
        Language("es", "Español"),
        Language("it", "Italiano"),
        Language("pt", "Português"),
        Language("pl", "Polski"),
        Language("tr", "Türkçe"),
        Language("sv", "Svenska"),
        Language("nl", "Nederlands"),
        Language("ru", "Русский"),
        Language("uk", "Українська"),
        Language("cs", "Čeština"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("zh", "中文"),
        Language("hi", "हिन्दी")
    )

    private const val PREFS = "translator_settings"
    private const val KEY_CACHED = "cached_source_languages"

    fun getSelected(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CACHED, setOf("en"))?.toMutableSet() ?: mutableSetOf("en")

    fun saveSelected(context: Context, codes: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_CACHED, codes.toSet()).apply()
    }

    fun cacheSelected(context: Context, codes: Set<String>, callback: (done: Boolean, error: String?) -> Unit) {
        val target = java.util.Locale.getDefault().language
        val filtered = codes.filter { it != target && languages.any { lang -> lang.code == it } }
        downloadNext(context, filtered, target, 0, callback)
    }

    private fun downloadNext(
        context: Context,
        sources: List<String>,
        target: String,
        index: Int,
        callback: (done: Boolean, error: String?) -> Unit
    ) {
        if (index >= sources.size) {
            callback(true, null)
            return
        }
        val source = sources[index]
        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        val translator = Translation.getClient(options)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                downloadNext(context, sources, target, index + 1, callback)
            }
            .addOnFailureListener { e ->
                translator.close()
                callback(false, "$source: ${e.message ?: "ошибка загрузки"}")
            }
    }

    fun removeUnselected(context: Context, selected: Set<String>): Task<Void> {
        val target = java.util.Locale.getDefault().language
        val tasks = languages
            .map { it.code }
            .filter { it != target && !selected.contains(it) }
            .map { code ->
                RemoteModelManager.getInstance().deleteDownloadedModel(
                    TranslateRemoteModel.Builder(code).build()
                )
            }
        return if (tasks.isEmpty()) Tasks.forResult(null) else Tasks.whenAll(tasks)
    }
}
