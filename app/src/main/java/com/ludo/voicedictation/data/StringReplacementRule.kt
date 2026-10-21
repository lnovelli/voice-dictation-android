package com.ludo.voicedictation.data

import java.util.Locale

/** One final-transcript rule: any listed phrase maps to the same configured output. */
data class StringReplacementRule(
    val inputs: List<String>,
    val output: String
)

/** Shared bounds and canonicalization for UI input, persisted data, and matching. */
object StringReplacementRules {
    const val MAX_RULES = 50
    const val MAX_INPUTS_PER_RULE = 20
    const val MAX_TOTAL_INPUTS = 200
    const val MAX_TEXT_LENGTH = 120

    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun normalizeInput(value: String): String? = normalizeText(value)

    fun normalizeOutput(value: String): String? = normalizeText(value)

    fun normalizeText(value: String): String? {
        val normalized = value.trim().replace(whitespace, " ")
        if (normalized.isEmpty() || normalized.length > MAX_TEXT_LENGTH) return null
        if (normalized.any { Character.isISOControl(it) }) return null
        return normalized
    }

    /** Dedupe ambiguous aliases globally; the first configured rule owns a duplicate. */
    fun normalize(rules: Collection<StringReplacementRule>): List<StringReplacementRule> {
        val result = mutableListOf<StringReplacementRule>()
        val seenInputs = mutableSetOf<String>()
        var totalInputs = 0

        for (rule in rules) {
            if (result.size >= MAX_RULES || totalInputs >= MAX_TOTAL_INPUTS) break
            val output = normalizeOutput(rule.output) ?: continue
            val inputs = mutableListOf<String>()
            val local = mutableSetOf<String>()
            for (candidate in rule.inputs) {
                val input = normalizeInput(candidate) ?: continue
                val key = input.lowercase(Locale.ROOT)
                if (key in local || key in seenInputs) continue
                if (inputs.size >= MAX_INPUTS_PER_RULE || totalInputs >= MAX_TOTAL_INPUTS) break
                local.add(key)
                seenInputs.add(key)
                inputs.add(input)
                totalInputs++
            }
            if (inputs.isNotEmpty()) result.add(StringReplacementRule(inputs, output))
        }
        return result
    }
}
