package com.ludo.voicedictation.data

import android.content.SharedPreferences
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal

/** Plaintext, versioned settings only; never includes history or recordings. */
internal object SettingsBackup {
    private const val VERSION = 1
    private const val MAX_JSON_CHARS = 1_000_000
    private val booleans = setOf(
        "service_enabled", "streaming_preview_enabled", "avoid_autofill_overlay_collisions",
        "always_show_overlay", "vocabulary_spelling_enabled", "acoustic_vocabulary_enabled",
        "transcript_removal_enabled", "string_replacement_enabled", "transcript_polishing_enabled",
        "static_numeric_corrections_enabled", "polishing_auto_number_sequences"
    )
    private val ints = setOf(
        "asr_thread_count", "history_limit", "overlay_opacity", "overlay_scale_percent",
        "acoustic_max_active_paths", "polishing_minimum_words", "overlay_x", "overlay_y",
        "overlay_x_portrait", "overlay_y_portrait", "overlay_x_landscape", "overlay_y_landscape"
    )
    private val strings = setOf(
        "selected_model", "mlkit_locale_tag", "preferred_microphone_v1", "transcript_removal_words",
        "transcript_polishing_endpoint", "transcript_polishing_model", "transcript_polishing_system_prompt"
    )
    private val settingsKeys = booleans + ints + strings + setOf(
        "acoustic_hotwords_score", "vocabulary", "string_replacement_rules"
    )
    private val positionKeys = setOf("x_portrait", "y_portrait", "x_landscape", "y_landscape")
    private val locales = setOf(
        "ar-SA", "cmn-Hans-CN", "cmn-Hant-TW", "da-DK", "de-DE", "en-US", "es-ES",
        "fr-FR", "hi-IN", "id-ID", "it-IT", "ja-JP", "ko-KR", "nl-NL", "pl-PL",
        "pt-PT", "ru-RU", "sv-SE", "th-TH", "tr-TR", "vi-VN"
    )

    fun export(prefs: SharedPreferences, positions: SharedPreferences): String {
        val stored = prefs.all
        val settings = JSONObject()
        for (key in settingsKeys) {
            val raw = stored[key] ?: continue
            // Never report a successful backup that silently drops a stored setting.
            val candidate = when (key) {
                "vocabulary" -> (raw as? Set<*>)?.sortedBy { it.toString() } ?: invalid()
                "string_replacement_rules" -> parse(raw as? String ?: invalid())
                else -> raw
            }
            val value = validateSetting(key, candidate)
            settings.put(key, jsonValue(value))
        }
        val positionObject = JSONObject()
        val savedPositions = positions.all
        for (key in positionKeys) {
            val value = savedPositions[key] ?: continue
            positionObject.put(key, value as? Int ?: invalid())
        }
        val result = JSONObject().put("version", VERSION).put("settings", settings)
            .put("live_transcript_position", positionObject).toString()
        require(result.length <= MAX_JSON_CHARS && result.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
            "Settings backup is too large"
        }
        return result
    }

    fun restore(json: String, prefs: SharedPreferences, positions: SharedPreferences): Boolean {
        val root = objectValue(parse(json))
        require(root.keys.all { it in setOf("version", "settings", "live_transcript_position") }) { "Invalid backup" }
        require(intValue(root["version"]) == VERSION) { "Unsupported backup version" }
        val settings = objectValue(root["settings"])
        require(settings.keys.all { it in settingsKeys }) { "Unknown setting" }
        val validated = settings.mapValues { (key, value) -> validateSetting(key, value) }
        val positionValues = if (root.containsKey("live_transcript_position")) {
            objectValue(root["live_transcript_position"])
        } else emptyMap()
        require(positionValues.keys.all { it in positionKeys }) { "Unknown position" }
        val validatedPositions = positionValues.mapValues { (_, value) -> intValue(value) }

        // SharedPreferences has no transaction spanning files. Validation is complete before
        // either editor is touched, but a disk failure or crash can split these two commits.
        val editor = prefs.edit()
        for (key in settingsKeys) {
            val value = validated[key]
            if (value == null) editor.remove(key) else when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                is List<*> -> editor.putString(key, jsonValue(value).toString())
                else -> error("Unexpected validated setting")
            }
        }
        val positionEditor = positions.edit()
        for (key in positionKeys) {
            val value = validatedPositions[key]
            if (value == null) positionEditor.remove(key) else positionEditor.putInt(key, value)
        }
        if (!editor.commit()) return false
        return positionEditor.commit()
    }

    private fun validateSetting(key: String, value: Any?): Any {
        if (key in booleans) return value as? Boolean ?: invalid()
        if (key in ints) {
            val number = intValue(value)
            val valid = when (key) {
                "asr_thread_count" -> number in 2..4
                "history_limit" -> number in 5..50 && number % 5 == 0
                "overlay_opacity" -> number in 40..100 && number % 5 == 0
                "overlay_scale_percent" -> number in 50..150 && number % 5 == 0
                "acoustic_max_active_paths" -> number in 2..8
                "polishing_minimum_words" -> number in 0..20
                else -> true // Coordinates can be negative and are clamped to the display by the overlay.
            }
            require(valid) { "Invalid setting value" }
            return number
        }
        if (key in strings) {
            val text = value as? String ?: invalid()
            val valid = when (key) {
                "selected_model" -> text in setOf(AppPreferences.MODEL_NEMOTRON_STREAMING,
                    AppPreferences.MODEL_NEMOTRON_STREAMING_560MS, AppPreferences.MODEL_MLKIT_ADVANCED)
                "mlkit_locale_tag" -> text in locales
                "preferred_microphone_v1" -> text.matches(Regex("[0-9a-f]{64}"))
                "transcript_polishing_endpoint" -> text.length <= 2048
                "transcript_polishing_model" -> text.length in 1..128
                "transcript_polishing_system_prompt" -> text.isNotBlank() && text.length <= 12_000
                "transcript_removal_words" -> text.length <= 8192
                else -> false
            }
            require(valid) { "Invalid setting value" }
            return text
        }
        if (key == "acoustic_hotwords_score") {
            val number = when (value) {
                is BigDecimal -> value.toDouble()
                is Float -> value.toDouble()
                is Double -> value
                is Int -> value.toDouble()
                else -> invalid()
            }
            require(number.isFinite() && number in 1.0..5.0) { "Invalid setting value" }
            return number.toFloat()
        }
        if (key == "vocabulary") {
            val list = value as? List<*> ?: invalid()
            require(list.all { it is String && it.isNotBlank() } && list.distinct().size == list.size) { "Invalid vocabulary" }
            return list.map { it as String }.toSet()
        }
        if (key == "string_replacement_rules") {
            val list = value as? List<*> ?: invalid()
            val rules = list.map { item ->
                val obj = objectValue(item)
                require(obj.keys == setOf("inputs", "output")) { "Invalid replacement rule" }
                val inputs = (obj["inputs"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
                StringReplacementRule(inputs, obj["output"] as? String ?: invalid())
            }
            require(StringReplacementRules.normalize(rules) == rules) { "Invalid replacement rules" }
            return list
        }
        return invalid()
    }

    private fun intValue(value: Any?): Int = try {
        when (value) {
            is Int -> value
            is BigDecimal -> value.intValueExact()
            else -> invalid()
        }
    } catch (_: ArithmeticException) { invalid() }

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid settings backup")

    private fun jsonValue(value: Any): Any = when (value) {
        is Set<*> -> JSONArray(value.toList())
        is List<*> -> JSONArray(value)
        else -> value
    }

    /** Strict streaming parse: reject duplicate fields, trailing content and excessive nesting. */
    private fun parse(json: String): Any {
        require(json.length <= MAX_JSON_CHARS) { "Settings backup is too large" }
        try {
            val reader = JsonReader(StringReader(json))
            reader.isLenient = false
            fun read(depth: Int): Any {
                require(depth <= 8) { "Invalid settings backup" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val map = linkedMapOf<String, Any>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(!map.containsKey(name)) { "Duplicate field" }
                            map[name] = read(depth + 1)
                        }
                        reader.endObject()
                        map
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        val list = mutableListOf<Any>()
                        reader.beginArray()
                        while (reader.hasNext()) list.add(read(depth + 1))
                        reader.endArray()
                        list
                    }
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NUMBER -> BigDecimal(reader.nextString())
                    else -> invalid()
                }
            }
            val result = read(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing content" }
            reader.close()
            return result
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid settings backup")
        }
    }
}
