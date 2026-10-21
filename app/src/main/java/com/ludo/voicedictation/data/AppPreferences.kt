package com.ludo.voicedictation.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val id: String,
    val timestamp: Long,
    val text: String
)

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "voice_dictation_prefs"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_OVERLAY_SCALE = "overlay_scale_percent"
        private const val KEY_VOCABULARY_SPELLING = "vocabulary_spelling_enabled"
        private const val KEY_ACOUSTIC_VOCABULARY_ENABLED = "acoustic_vocabulary_enabled"
        private const val KEY_ACOUSTIC_MAX_ACTIVE_PATHS = "acoustic_max_active_paths"
        private const val KEY_ACOUSTIC_HOTWORDS_SCORE = "acoustic_hotwords_score"

        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_X_PORTRAIT = "overlay_x_portrait"
        private const val KEY_OVERLAY_Y_PORTRAIT = "overlay_y_portrait"
        private const val KEY_OVERLAY_X_LANDSCAPE = "overlay_x_landscape"
        private const val KEY_OVERLAY_Y_LANDSCAPE = "overlay_y_landscape"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_HISTORY = "history"
        private const val KEY_TRANSCRIPT_REMOVAL_ENABLED = "transcript_removal_enabled"
        private const val KEY_TRANSCRIPT_REMOVAL_WORDS = "transcript_removal_words"
        val DEFAULT_TRANSCRIPT_REMOVALS: List<String> = listOf("um", "uhm", "uh")

        const val MODEL_PARAKEET_V2 = "parakeet_v2"
        const val MODEL_PARAKEET_V3 = "parakeet_v3"
    }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", true)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL, MODEL_PARAKEET_V2) ?: MODEL_PARAKEET_V2
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, 10)
        set(value) = prefs.edit().putInt(KEY_HISTORY_LIMIT, value).apply()

    var overlayOpacity: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, 80)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_OPACITY, value).apply()

    var overlayScalePercent: Int
        get() = prefs.getInt(KEY_OVERLAY_SCALE, 100).coerceIn(50, 150)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_SCALE, value.coerceIn(50, 150)).apply()

    /** Read a complete orientation-specific pair, migrating a legacy position into the first mode opened. */
    fun savedOverlayPosition(isLandscape: Boolean): Pair<Int, Int>? {
        val (xKey, yKey) = if (isLandscape) {
            KEY_OVERLAY_X_LANDSCAPE to KEY_OVERLAY_Y_LANDSCAPE
        } else {
            KEY_OVERLAY_X_PORTRAIT to KEY_OVERLAY_Y_PORTRAIT
        }
        val hasX = prefs.contains(xKey)
        val hasY = prefs.contains(yKey)
        if (hasX && hasY) return prefs.getInt(xKey, -1) to prefs.getInt(yKey, -1)
        if (hasX || hasY) return null

        // Older builds stored a single shared position. Keep it for the mode active on
        // upgrade, then let the other orientation start at its independent default.
        val hasLegacyX = prefs.contains(KEY_OVERLAY_X)
        val hasLegacyY = prefs.contains(KEY_OVERLAY_Y)
        if (!hasLegacyX && !hasLegacyY) return null
        val editor = prefs.edit().remove(KEY_OVERLAY_X).remove(KEY_OVERLAY_Y)
        if (!hasLegacyX || !hasLegacyY) {
            editor.commit()
            return null
        }
        val x = prefs.getInt(KEY_OVERLAY_X, -1)
        val y = prefs.getInt(KEY_OVERLAY_Y, -1)
        if (!editor.putInt(xKey, x).putInt(yKey, y).commit()) return null
        return x to y
    }

    fun persistOverlayPosition(x: Int, y: Int, isLandscape: Boolean): Boolean {
        val (xKey, yKey) = if (isLandscape) {
            KEY_OVERLAY_X_LANDSCAPE to KEY_OVERLAY_Y_LANDSCAPE
        } else {
            KEY_OVERLAY_X_PORTRAIT to KEY_OVERLAY_Y_PORTRAIT
        }
        return prefs.edit().putInt(xKey, x).putInt(yKey, y).commit()
    }

    // Acoustic bias is experimental and stays opt-in; default settings preserve the unbiased decoder.
    var acousticVocabularyEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACOUSTIC_VOCABULARY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ACOUSTIC_VOCABULARY_ENABLED, value).apply()

    var acousticMaxActivePaths: Int
        get() = prefs.getInt(KEY_ACOUSTIC_MAX_ACTIVE_PATHS, 4).coerceIn(2, 8)
        set(value) = prefs.edit().putInt(KEY_ACOUSTIC_MAX_ACTIVE_PATHS, value.coerceIn(2, 8)).apply()

    var acousticHotwordsScore: Float
        get() = prefs.getFloat(KEY_ACOUSTIC_HOTWORDS_SCORE, 1.0f)
            .let { if (it.isFinite()) it.coerceIn(1.0f, 5.0f) else 1.0f }
        set(value) = prefs.edit().putFloat(
            KEY_ACOUSTIC_HOTWORDS_SCORE,
            if (value.isFinite()) value.coerceIn(1.0f, 5.0f) else 1.0f
        ).apply()

    // Legacy hotwords_score remains stored but is intentionally never read by ASR.
    var vocabularySpellingEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOCABULARY_SPELLING, true)
        set(value) = prefs.edit().putBoolean(KEY_VOCABULARY_SPELLING, value).apply()

    var transcriptRemovalEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPT_REMOVAL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCRIPT_REMOVAL_ENABLED, value).apply()

    // Missing key gets conservative defaults; an explicitly empty list stays empty.
    var transcriptRemovalWords: List<String>
        get() = (prefs.getString(KEY_TRANSCRIPT_REMOVAL_WORDS, null)
            ?: DEFAULT_TRANSCRIPT_REMOVALS.joinToString("\n")).lines().filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TRANSCRIPT_REMOVAL_WORDS, value.joinToString("\n")).apply()

    fun getVocabulary(): Set<String> {
        return prefs.getStringSet(KEY_VOCABULARY, emptySet()) ?: emptySet()
    }

    fun addWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val current = getVocabulary().toMutableSet()
        current.add(trimmed)
        prefs.edit().putStringSet(KEY_VOCABULARY, current).apply()
    }

    fun removeWord(word: String) {
        val current = getVocabulary().toMutableSet()
        current.remove(word)
        prefs.edit().putStringSet(KEY_VOCABULARY, current).apply()
    }

    fun clearVocabulary() {
        prefs.edit().remove(KEY_VOCABULARY).apply()
    }

    fun getHistory(): List<HistoryItem> {
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        text = obj.optString("text", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun addHistoryItem(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = getHistory().toMutableList()
        val newItem = HistoryItem(
            id = System.currentTimeMillis().toString(),
            timestamp = System.currentTimeMillis(),
            text = trimmed
        )
        current.add(0, newItem)
        val limit = historyLimit
        while (current.size > limit) {
            current.removeAt(current.size - 1)
        }
        saveHistory(current)
    }

    fun removeHistoryItem(id: String) {
        val current = getHistory().toMutableList()
        current.removeAll { it.id == id }
        saveHistory(current)
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("timestamp", item.timestamp)
            obj.put("text", item.text)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
}
