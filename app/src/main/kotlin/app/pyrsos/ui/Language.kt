// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The app's language: the phone's own, or one picked in Settings. Stays on this phone. */
enum class Language(val tag: String?) { AUTO(null), GREEK("el"), ENGLISH("en") }

object LanguagePrefs {
    private const val FILE = "ui"
    private const val KEY = "language"

    fun load(context: Context): Language =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: Language.AUTO

    fun save(context: Context, language: Language) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, language.name).apply()

    /** [base] with the picked language applied; [base] itself when the phone's language is used. */
    fun wrap(base: Context): Context {
        val tag = load(base).tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
