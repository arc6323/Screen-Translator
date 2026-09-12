package com.arc6323.screentranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val boxes = linkedMapOf<String, CheckBox>()

    private val languages = linkedMapOf(
        "en" to "Английский",
        "de" to "Немецкий",
        "fr" to "Французский",
        "es" to "Испанский",
        "it" to "Итальянский",
        "pt" to "Португальский",
        "pl" to "Польский",
        "cs" to "Чешский",
        "nl" to "Нидерландский",
        "sv" to "Шведский",
        "da" to "Датский",
        "no" to "Норвежский",
        "fi" to "Финский",
        "tr" to "Турецкий",
        "uk" to "Украинский",
        "ja" to "Японский",
        "ko" to "Корейский",
        "zh" to "Китайский",
        "ar" to "Арабский",
        "hi" to "Хинди"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Настройки Screen Translator"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        content.addView(title)

        val target = LocaleInfo.deviceLanguageName(this)
        content.addView(TextView(this).apply {
            text = "Язык перевода: $target (язык телефона)\n\nВыберите языки, модели которых нужно держать на телефоне. Приложение не будет автоматически скачивать остальные языки во время Live-перевода."
            textSize = 16f
            setPadding(0, 24, 0, 20)
        })

        languages.forEach { (code, name) ->
            val box = CheckBox(this).apply {
                text = "$name ($code)"
                textSize = 16f
                isChecked = prefs.getStringSet("cached_sources", setOf("en"))?.contains(code) == true
                setOnCheckedChangeListener { _, checked -> saveSelection(code, checked) }
            }
            boxes[code] = box
            content.addView(box)
        }

        val download = Button(this).apply { text = "СКАЧАТЬ ВЫБРАННЫЕ МОДЕЛИ (Wi‑Fi)" }
        content.addView(download)
        download.setOnClickListener { downloadSelected() }

        val delete = Button(this).apply { text = "УДАЛИТЬ НЕВЫБРАННЫЕ МОДЕЛИ" }
        content.addView(delete)
        delete.setOnClickListener { deleteUnselected() }

        content.addView(TextView(this).apply {
            text = "\nБатарея"
            textSize = 20f
        })
        val batteryStatus = TextView(this).apply { textSize = 15f }
        content.addView(batteryStatus)
        updateBatteryStatus(batteryStatus)

        val battery = Button(this).apply { text = "РАЗРЕШИТЬ РАБОТУ БЕЗ ОГРАНИЧЕНИЙ" }
        content.addView(battery)
        battery.setOnClickListener { requestBatteryExemption() }

        val inactive = Button(this).apply { text = "НАСТРОЙКИ НЕАКТИВНОГО ПРИЛОЖЕНИЯ" }
        content.addView(inactive)
        inactive.setOnClickListener { openAppDetails() }

        content.addView(TextView(this).apply {
            text = "\nВажно: Android позволяет приложению попросить исключение из оптимизации батареи, но окончательное решение принимает пользователь/система. Настройку «не закрывать в неактивный период» приложение не может принудительно включить — на некоторых телефонах она относится к системным или фирменным ограничениям."
            textSize = 14f
        })

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun saveSelection(code: String, checked: Boolean) {
        val set = prefs.getStringSet("cached_sources", setOf("en"))?.toMutableSet() ?: mutableSetOf()
        if (checked) set.add(code) else set.remove(code)
        prefs.edit().putStringSet("cached_sources", set).apply()
    }

    private fun selectedCodes(): Set<String> = boxes.filterValues { it.isChecked }.keys

    private fun downloadSelected() {
        val target = Locale.getDefault().language
        val selected = selectedCodes().toMutableSet().apply { add(target) }
        val manager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().requireWifi().build()
        Toast.makeText(this, "Скачивание выбранных моделей началось", Toast.LENGTH_SHORT).show()
        selected.forEach { code ->
            val language = TranslateLanguage.fromLanguageTag(code) ?: return@forEach
            val model = TranslateRemoteModel.Builder(language).build()
            manager.download(model, conditions)
                .addOnSuccessListener { }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Не удалось скачать $code: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun deleteUnselected() {
        val keep = selectedCodes().toMutableSet().apply { add(Locale.getDefault().language) }
        val manager = RemoteModelManager.getInstance()
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                models.forEach { model ->
                    val code = model.language
                    if (!keep.contains(code)) {
                        manager.deleteDownloadedModel(model)
                    }
                }
                Toast.makeText(this, "Невыбранные модели удаляются", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e -> Toast.makeText(this, "Не удалось получить список моделей: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun requestBatteryExemption() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Для приложения уже отключена оптимизация батареи", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        val root = findViewById<ScrollView>(android.R.id.content)?.getChildAt(0) as? LinearLayout
        val status = root?.let { layout ->
            (0 until layout.childCount).mapNotNull { layout.getChildAt(it) as? TextView }.firstOrNull { it.text.toString().startsWith("Оптимизация батареи") }
        }
        if (status != null) updateBatteryStatus(status)
    }

    private fun updateBatteryStatus(view: TextView) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            view.text = "Оптимизация батареи: не требуется"
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        view.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Оптимизация батареи: БЕЗ ОГРАНИЧЕНИЙ"
        } else {
            "Оптимизация батареи: включена"
        }
    }
}

private object LocaleInfo {
    fun deviceLanguageName(activity: Activity): String {
        val code = java.util.Locale.getDefault().language
        return when (code) {
            "ru" -> "Русский"
            "en" -> "English"
            "de" -> "Deutsch"
            "fr" -> "Français"
            "es" -> "Español"
            "it" -> "Italiano"
            "zh" -> "中文"
            "ja" -> "日本ский"
            "ko" -> "한국어"
            else -> code
        }
    }
}
