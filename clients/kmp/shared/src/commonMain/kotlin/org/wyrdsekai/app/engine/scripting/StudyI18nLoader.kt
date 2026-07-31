package org.wyrdsekai.app.engine.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.platform.readBundledText

/**
 * Loads Study room translations from bundled JSON resource files.
 *
 * JSON files are in commonMain/resources/i18n/study_{lang}.json,
 * copied from the server's scripts/i18n/{lang}.json (study.* keys only).
 * This is the single source of truth for phone room i18n.
 *
 * Falls back to English if the requested locale is unavailable.
 */
object StudyI18nLoader {

    private val cache = mutableMapOf<String, Map<String, String>>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Load translations for a locale. Returns a map of key → translated string.
     * Falls back to English for missing locales.
     */
    fun load(locale: String): Map<String, String> {
        cache[locale]?.let { return it }

        val loaded = tryLoadFromResource(locale)
            ?: (if (locale != "en") tryLoadFromResource("en") else null)
            ?: emptyMap()

        cache[locale] = loaded
        return loaded
    }

    /** Clear the cache (e.g. when locale changes). */
    fun clearCache() { cache.clear() }

    private fun tryLoadFromResource(lang: String): Map<String, String>? {
        return try {
            val resourcePath = "/i18n/study_$lang.json"
            val text = readBundledText(resourcePath) ?: return null
            parseJsonMap(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJsonMap(jsonText: String): Map<String, String> {
        val obj = json.parseToJsonElement(jsonText) as? JsonObject ?: return emptyMap()
        return obj.entries.associate { (key, value) ->
            key to value.jsonPrimitive.content
        }
    }
}
