package com.arc6323.screentranslator

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale

/** Application-scoped state, independent of any Activity or dialog. Main thread only. */
object LanguageCacheManager {
    private const val PREFS = "translator_prefs"
    private const val KEY_LANGUAGES = "cached_languages"
    const val ESTIMATED_MODEL_MB = 30
    data class Language(val code: String, val name: String, val screenOcr: Boolean = true)
    val languages = listOf(
        Language("ru", "Русский", false), Language("en", "English"),
        Language("de", "Deutsch"), Language("fr", "Français"), Language("es", "Español"),
        Language("it", "Italiano"), Language("pt", "Português"), Language("pl", "Polski"),
        Language("uk", "Українська", false), Language("cs", "Čeština"), Language("nl", "Nederlands"),
        Language("sv", "Svenska"), Language("tr", "Türkçe"), Language("ro", "Română"),
        Language("el", "Ελληνικά", false), Language("ar", "العربية", false),
        Language("he", "עברית", false), Language("hi", "हिन्दी"), Language("ja", "日本語"),
        Language("ko", "한국어"), Language("zh", "中文"), Language("vi", "Tiếng Việt"),
        Language("id", "Bahasa Indonesia"), Language("th", "ไทย", false),
        Language("da", "Dansk"), Language("no", "Norsk"), Language("fi", "Suomi"),
        Language("bg", "Български", false), Language("hu", "Magyar"), Language("sk", "Slovenčina"),
        Language("ca", "Català"), Language("fa", "فارسی", false)
    )
    private val supported = languages.map { it.code }.toSet()
    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val manager get() = RemoteModelManager.getInstance()
    var downloaded: Set<String> = setOf("en", "ru")
        private set
    var checking = false
        private set
    var lastError: String? = null
        private set
    private var revision = 0L
    private val deleting = mutableSetOf<String>()
    private val queue = ModelDownloadQueue(::ensureModel) { notifyChanged() }
    val downloads get() = queue.snapshot()
    fun initialize(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            saveSelected(app, selected(app))
            refresh()
        }
    }
    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    fun languageName(code: String) = languages.firstOrNull { it.code == code }?.name ?: code
    fun supportsScreenOcr(code: String) = languages.any { it.code == code && it.screenOcr }
    fun targetLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val chosen = prefs.getString("target_language", null)
        if (chosen != null && chosen in supported) return chosen
        val locale = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(android.app.LocaleManager::class.java)?.systemLocales?.let {
                if (it.isEmpty) null else it[0]
            }
        } else Locale.getDefault()
        val code = locale?.language?.lowercase(Locale.ROOT) ?: "en"
        return if (code in supported) code else "en"
    }
    fun targetLanguageName(context: Context) = languageName(targetLanguage(context))
    fun setTargetLanguage(context: Context, code: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (code == null) edit.remove("target_language")
        else if (code in supported) edit.putString("target_language", code)
        edit.apply()
        notifyChanged()
    }
    fun selected(context: Context): Set<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_LANGUAGES, emptySet()).orEmpty()
        return saved.filter { it in supported }.toSet() + setOf("ru", "en")
    }
    fun saveSelected(context: Context, codes: Set<String>) {
        val valid = codes.filter { it in supported }.toSet() + setOf("ru", "en")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_LANGUAGES, valid).apply()
        notifyChanged()
    }
    fun setSelected(context: Context, code: String, enabled: Boolean) {
        if (code !in supported || code == "ru" || code == "en") return
        val codes = selected(context).toMutableSet()
        if (enabled) codes.add(code) else codes.remove(code)
        saveSelected(context, codes)
    }
    fun wifiOnly(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wifi_only", false)
    fun setWifiOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("wifi_only", value).apply()
    }
    fun refresh() {
        if (!::app.isInitialized || checking) return
        checking = true
        val expectedRevision = revision
        notifyChanged()
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                checking = false
                if (revision == expectedRevision) {
                    downloaded = models.map { it.language }.toSet() + setOf("en", "ru")
                    lastError = null
                }
                notifyChanged()
                if (revision != expectedRevision) refresh()
            }
            .addOnFailureListener {
                checking = false
                lastError = "Не удалось проверить модели: ${it.localizedMessage ?: "ошибка ML Kit"}"
                notifyChanged()
            }
    }
    fun download(context: Context, requested: Set<String>) {
        initialize(context)
        saveSelected(app, selected(app) + requested)
        lastError = null
        val required = (requested + setOf("ru", "en", targetLanguage(app))).filter { it in supported }
        queue.enqueue(required)
    }
    fun pause() = queue.pause()
    fun resume() = queue.resume()
    fun retryFailed() = queue.retryFailed()
    fun isDeleting(code: String) = code in deleting
    fun delete(context: Context, code: String) {
        initialize(context)
        if (code in setOf("ru", "en", targetLanguage(app)) || downloads.active ||
            TranslationStatus.state.running || code in deleting) return
        val model = model(code) ?: return
        deleting.add(code)
        lastError = null
        notifyChanged()
        manager.deleteDownloadedModel(model)
            .addOnSuccessListener {
                revision++
                downloaded = downloaded - code
                deleting.remove(code)
                setSelected(app, code, false)
                notifyChanged()
            }
            .addOnFailureListener {
                deleting.remove(code)
                lastError = "Не удалось удалить ${languageName(code)}: ${it.localizedMessage ?: "ошибка"}"
                notifyChanged()
            }
    }
    private fun model(code: String): TranslateRemoteModel? =
        TranslateLanguage.fromLanguageTag(code)?.let { TranslateRemoteModel.Builder(it).build() }
    private fun ensureModel(code: String, done: (String?) -> Unit) {
        if (code in setOf("en", "ru")) { done(null); return }
        val model = model(code)
        if (model == null) { done("Язык не поддерживается"); return }
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly(app)) requireWifi()
        }.build()
        fun success() {
            revision++
            downloaded = downloaded + code
            done(null)
        }
        fun failure(error: Exception) {
            done(error.localizedMessage ?: "Не удалось скачать модель")
        }
        manager.isModelDownloaded(model)
            .addOnSuccessListener { exists ->
                if (exists) success()
                else manager.download(model, conditions)
                    .addOnSuccessListener { success() }
                    .addOnFailureListener { failure(it) }
            }
            .addOnFailureListener { failure(it) }
    }
    private fun notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { notifyChanged() }
            return
        }
        listeners.toList().forEach { it() }
    }
}
