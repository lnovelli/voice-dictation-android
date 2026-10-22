package com.ludo.voicedictation.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(
    val id: String,
    val timestamp: Long,
    val text: String,
    val audioFileName: String? = null
)

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
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
        private const val KEY_STRING_REPLACEMENT_RULES = "string_replacement_rules"
        private const val KEY_TRANSCRIPT_POLISHING_ENABLED = "transcript_polishing_enabled"
        private const val KEY_TRANSCRIPT_POLISHING_ENDPOINT = "transcript_polishing_endpoint"
        private const val KEY_TRANSCRIPT_POLISHING_MODEL = "transcript_polishing_model"
        const val DEFAULT_TRANSCRIPT_POLISHING_ENDPOINT = "http://127.0.0.1:11434"
        const val DEFAULT_TRANSCRIPT_POLISHING_MODEL = "default"
        val DEFAULT_TRANSCRIPT_REMOVALS: List<String> = listOf("um", "uhm", "uh")

        const val MODEL_PARAKEET_V2 = "parakeet_v2"
        const val MODEL_PARAKEET_V3 = "parakeet_v3"
    }

    var transcriptPolishingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRANSCRIPT_POLISHING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSCRIPT_POLISHING_ENABLED, value).apply()

    var transcriptPolishingEndpoint: String
        get() = prefs.getString(KEY_TRANSCRIPT_POLISHING_ENDPOINT, DEFAULT_TRANSCRIPT_POLISHING_ENDPOINT)
            ?.takeIf { it.length <= 2048 } ?: DEFAULT_TRANSCRIPT_POLISHING_ENDPOINT
        set(value) = prefs.edit().putString(KEY_TRANSCRIPT_POLISHING_ENDPOINT, value.take(2048)).apply()

    var transcriptPolishingModel: String
        get() = prefs.getString(KEY_TRANSCRIPT_POLISHING_MODEL, DEFAULT_TRANSCRIPT_POLISHING_MODEL)
            ?.takeIf { it.length in 1..128 } ?: DEFAULT_TRANSCRIPT_POLISHING_MODEL
        set(value) = prefs.edit().putString(KEY_TRANSCRIPT_POLISHING_MODEL, value.take(128)).apply()

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

    fun getStringReplacementRules(): List<StringReplacementRule> {
        val serialized = prefs.getString(KEY_STRING_REPLACEMENT_RULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            val rules = mutableListOf<StringReplacementRule>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val inputArray = obj.optJSONArray("inputs") ?: continue
                val inputs = (0 until inputArray.length()).mapNotNull { inputArray.opt(it) as? String }
                val output = obj.opt("output") as? String ?: continue
                rules.add(StringReplacementRule(inputs, output))
            }
            StringReplacementRules.normalize(rules)
        }.getOrDefault(emptyList())
    }

    fun setStringReplacementRules(rules: Collection<StringReplacementRule>) {
        val normalized = StringReplacementRules.normalize(rules)
        val array = JSONArray()
        normalized.forEach { rule ->
            val inputs = JSONArray().apply { rule.inputs.forEach(::put) }
            array.put(JSONObject().put("inputs", inputs).put("output", rule.output))
        }
        prefs.edit().putString(KEY_STRING_REPLACEMENT_RULES, array.toString()).apply()
    }

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
                        text = obj.optString("text", ""),
                        audioFileName = (obj.opt("audio_file") as? String)
                            ?.takeIf(HistoryAudioStore::isSafeFileName)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun addHistoryItem(text: String, audioFileName: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = getHistory().toMutableList()
        val timestamp = System.currentTimeMillis()
        val newItem = HistoryItem(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            text = trimmed,
            audioFileName = audioFileName?.takeIf(HistoryAudioStore::isSafeFileName)
        )
        current.add(0, newItem)
        val evictedAudioFiles = mutableListOf<String?>()
        val limit = historyLimit
        while (current.size > limit) {
            evictedAudioFiles.add(current.removeAt(current.size - 1).audioFileName)
        }
        saveHistory(current)
        evictedAudioFiles.forEach { HistoryAudioStore.delete(appContext, it) }
    }

    /** Replaces only the transcript; preserves the history ID, timestamp and original recording. */
    fun updateHistoryTranscript(id: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val current = getHistory().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        current[index] = current[index].copy(text = trimmed)
        saveHistory(current)
        return true
    }

    fun removeHistoryItem(id: String) {
        val current = getHistory().toMutableList()
        val removed = current.filter { it.id == id }
        if (removed.isEmpty()) return
        current.removeAll { it.id == id }
        saveHistory(current)
        removed.forEach { HistoryAudioStore.delete(appContext, it.audioFileName) }
    }

    fun clearHistory() {
        val audioFiles = getHistory().mapNotNull { it.audioFileName }
        prefs.edit().remove(KEY_HISTORY).apply()
        audioFiles.forEach { HistoryAudioStore.delete(appContext, it) }
    }

    /** Removes audio left behind by a process crash, while preserving all history-linked clips. */
    fun cleanupOrphanedHistoryAudio() {
        HistoryAudioStore.retain(appContext, getHistory().map { it.audioFileName })
    }

    private fun saveHistory(list: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("timestamp", item.timestamp)
            obj.put("text", item.text)
            item.audioFileName?.takeIf(HistoryAudioStore::isSafeFileName)?.let {
                obj.put("audio_file", it)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
}
