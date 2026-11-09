package app.kotakbase

import android.content.Context
import android.icu.text.PluralRules
import android.icu.util.ULocale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * The app in the user's language, from the desktop app's translations (assets/locales, made by scripts/locales.mjs):
 * t() of the English text gives the translation, the English text being the key, so a missing one shows English.
 */
object I18n {
    val LANGUAGES = linkedMapOf(
        "en" to "English", "uk" to "Українська", "kk" to "Қазақша", "ru" to "Русский", "de" to "Deutsch",
        "es" to "Español", "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "ja" to "日本語"
    )

    var lang by mutableStateOf("en")
        private set
    private var dict = JSONObject()
    private var en = JSONObject() // English plural forms
    private var plurals = PluralRules.forLocale(ULocale.ENGLISH)
    private lateinit var assets: android.content.res.AssetManager

    /** The language picked in Settings, else the first of the system's that Kotakbase has, else English. */
    fun pick(saved: String?, system: List<Locale> = Locale.getDefault().let(::listOf)): String {
        if (saved != null && saved in LANGUAGES) return saved
        for (l in system) {
            val tag = l.toLanguageTag().lowercase()
            if (tag.startsWith("zh")) return if (Regex("hant|tw|hk|mo").containsMatchIn(tag)) "zh-TW" else "zh-CN"
            if (l.language in LANGUAGES) return l.language
        }
        return "en"
    }

    fun init(context: Context) {
        assets = context.assets
        en = read("en")
        set(pick(Store.language, android.os.LocaleList.getDefault().let { ls -> List(ls.size()) { ls[it] } }))
    }

    fun set(code: String) {
        dict = if (code == "en") JSONObject() else read(code)
        plurals = PluralRules.forLocale(ULocale.forLanguageTag(code))
        lang = code
    }

    private fun read(code: String) = runCatching { JSONObject(assets.open("locales/$code.json").bufferedReader().readText()) }.getOrDefault(JSONObject())

    fun t(text: String, vars: Map<String, Any?> = emptyMap()): String {
        lang // read, so screens redraw when the language changes
        val entry = dict.opt(text) ?: en.opt(text) ?: text
        val s = if (entry is JSONObject) {
            val n = (vars["n"] as? Number)?.toDouble() ?: 0.0
            entry.optString(plurals.select(n)).ifEmpty { entry.optString("other", text) }
        } else entry.toString()
        return fill(s, vars)
    }
}

fun t(text: String, vararg vars: Pair<String, Any?>) = I18n.t(text, vars.toMap())
