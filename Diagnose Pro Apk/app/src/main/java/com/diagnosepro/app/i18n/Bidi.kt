package com.diagnosepro.app.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Bidirectional text helpers.
 *
 * Fault codes ("P0301"), VINs, numbers with units ("2,450 rpm") and PID values must always be
 * shown left-to-right, even inside Hebrew sentences. We wrap them in Unicode directional
 * isolates (LRI U+2066 … PDI U+2069), which keep the run LTR without affecting the
 * surrounding RTL text — so "P0301" never turns into "0301P" and "2,450 rpm" never into "rpm 2,450".
 */
object Bidi {
    private const val LRI = '⁦'
    private const val PDI = '⁩'

    fun ltr(text: String): String = "$LRI$text$PDI"

    /** Value + unit, e.g. "2,450 rpm", isolated as one LTR run. */
    fun value(number: String, unit: String): String = ltr(if (unit.isEmpty()) number else "$number $unit")
}

enum class AppLanguage(val tag: String) { SYSTEM(""), HEBREW("he"), ENGLISH("en") }

/** Per-app language via AndroidX (works back to API 26 with autoStoreLocales). Switches instantly. */
object LocaleManager {
    fun set(lang: AppLanguage) {
        val list = if (lang == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang.tag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    fun current(): AppLanguage {
        val l = AppCompatDelegate.getApplicationLocales()
        if (l.isEmpty) return AppLanguage.SYSTEM
        return when (l[0]?.language) { "he", "iw" -> AppLanguage.HEBREW; "en" -> AppLanguage.ENGLISH; else -> AppLanguage.SYSTEM }
    }

    fun isHebrew(locale: Locale): Boolean = locale.language == "he" || locale.language == "iw"
}
