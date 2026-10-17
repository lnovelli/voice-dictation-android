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
        private const val KEY_HOTWORDS_SCORE = "hotwords_score"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_VOCABULARY = "vocabulary"
        private const val KEY_HISTORY = "history"

        const val MODEL_PARAKEET_V2 = "parakeet_v2"
        const val MODEL_PARAKEET_V3 = "parakeet_v3"
    }

    var selectedModelId: String
        get() = prefs.getString(KEY_SELECTED_MODEL, MODEL_PARAKEET_V2) ?: MODEL_PARAKEET_V2
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, 10)
        set(value) = prefs.edit().putInt(KEY_HISTORY_LIMIT, value).apply()

    var overlayOpacity: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, 80)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_OPACITY, value).apply()

    var hotwordsScore: Float
        get() = prefs.getFloat(KEY_HOTWORDS_SCORE, 2.0f)
        set(value) = prefs.edit().putFloat(KEY_HOTWORDS_SCORE, value).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

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
