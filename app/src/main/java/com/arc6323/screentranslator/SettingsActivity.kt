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
import java.util.Locale

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val boxes = linkedMapOf<String, CheckBox>()
    private var batteryStatus: TextView? = null

    private val languages = linkedMapOf(
        "en" to "Английский", "de" to "Немецкий", "fr" to "Французский", "es" to "Испанский",
        "it" to "Итальянский", "pt" to "Португальский", "pl" to "Польский", "cs" to "Чешский",
        "nl" to "Нидерландский", "sv" to "Шведский", "da" to "Датский", "no" to "Норвежский",
        "fi" to "Финский", "tr" to "Турецкий", "uk" to "Украинский", "ja" to "Японский",
        "ko" to "Корейский", "zh" to "Китайский", "ar" to "Арабский", "hi" to "Хинди"
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
        content.addView(TextView(this).apply {
            text = "Настройки Screen Translator"
            textSize = 24f
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "Язык перевода: ${LocaleInfo.deviceLanguageName()} (язык телефона)\n\nВыберите языки, модели которых нужно держать на телефоне. Live-перевод не будет сам скачивать остальные модели."
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

        content.addView(Button(this).apply {
            text = "СКАЧАТЬ ВЫБРАННЫЕ МОДЕЛИ (Wi‑Fi)"
            setOnClickListener { downloadSelected() }
        })
        content.addView(Button(this).apply {
            text = "УДАЛИТЬ НЕВЫБРАННЫЕ МОДЕЛИ"
            setOnClickListener { deleteUnselected() }
        })

        content.addView(TextView(this).apply { text = "\nБатарея"; textSize = 20f })
        batteryStatus = TextView(this).apply { textSize = 15f }
        content.addView(batteryStatus)
        updateBatteryStatus()

        content.addView(Button(this).apply {
            text = "РАЗРЕШИТЬ РАБОТУ БЕЗ ОГРАНИЧЕНИЙ"
            setOnClickListener { requestBatteryExemption() }
        })
        content.addView(Button(this).apply {
            text = "НАСТРОЙКИ НЕАКТИВНОГО ПРИЛОЖЕНИЯ"
            setOnClickListener { openAppDetails() }
        })
        content.addView(TextView(this).apply {
            text = "\nAndroid позволяет запросить исключение из оптимизации батареи, но окончательное решение принимает пользователь/система. Настройку «не закрывать в неактивный период» приложение не может принудительно включить: на разных телефонах это системное или фирменное ограничение."
            textSize = 14f
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun saveSelection(code: String, checked: Boolean) {
        val set = prefs.getStringSet("cached_sources", setOf("en"))?.toMutableSet() ?: mutableSetOf()
        if (checked) set.add(code) else set.remove(code)
        prefs.edit().putStringSet("cached_sources", set).apply()
    }

    fun selectedCodes(): Set<String> = boxes.filterValues { it.isChecked }.keys

    private fun downloadSelected() {
        val selected = selectedCodes().toMutableSet().apply { add(Locale.getDefault().language) }
        val manager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().requireWifi().build()
        Toast.makeText(this, "Скачивание выбранных моделей началось", Toast.LENGTH_SHORT).show()
        selected.forEach { code ->
            val language = TranslateLanguage.fromLanguageTag(code) ?: return@forEach
            val model = TranslateRemoteModel.Builder(language).build()
            manager.download(model, conditions)
                .addOnFailureListener { e -> Toast.makeText(this, "Не удалось скачать $code: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun deleteUnselected() {
        val keep = selectedCodes().toMutableSet().apply { add(Locale.getDefault().language) }
        val manager = RemoteModelManager.getInstance()
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                models.forEach { model -> if (!keep.contains(model.language)) manager.deleteDownloadedModel(model) }
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
        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        val view = batteryStatus ?: return
        if (android.os.Build.VERSION.SDK_INT < 23) {
            view.text = "Оптимизация батареи: не требуется"
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        view.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "Оптимизация батареи: БЕЗ ОГРАНИЧЕНИЙ" else "Оптимизация батареи: включена"
    }
}

private object LocaleInfo {
    fun deviceLanguageName(): String = when (Locale.getDefault().language) {
        "ru" -> "Русский"; "en" -> "English"; "de" -> "Deutsch"; "fr" -> "Français";
        "es" -> "Español"; "it" -> "Italiano"; "zh" -> "中文"; "ja" -> "日本ский"; "ko" -> "한국어";
        else -> Locale.getDefault().language
    }
}
