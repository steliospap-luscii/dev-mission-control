package com.devhub.sonar

import com.devhub.core.GateCondition

/** Turns raw SonarCloud gate conditions into human-readable lines. */
object SonarFormat {

    private val LABELS = mapOf(
        "new_reliability_rating" to "Reliability (new)",
        "reliability_rating" to "Reliability",
        "new_security_rating" to "Security (new)",
        "security_rating" to "Security",
        "new_maintainability_rating" to "Maintainability (new)",
        "sqale_rating" to "Maintainability",
        "new_security_hotspots_reviewed" to "Hotspots reviewed (new)",
        "security_hotspots_reviewed" to "Hotspots reviewed",
        "new_coverage" to "Coverage (new)",
        "coverage" to "Coverage",
        "new_duplicated_lines_density" to "Duplication (new)",
        "duplicated_lines_density" to "Duplication",
        "new_bugs" to "Bugs (new)",
        "new_vulnerabilities" to "Vulnerabilities (new)",
        "new_code_smells" to "Code smells (new)",
    )

    fun toCondition(c: Condition): GateCondition {
        val isRating = c.metricKey.endsWith("_rating")
        val isPercent = c.metricKey.contains("coverage") ||
            c.metricKey.contains("density") ||
            c.metricKey.contains("reviewed")
        return GateCondition(
            label = LABELS[c.metricKey] ?: prettify(c.metricKey),
            actual = formatValue(c.actualValue, isRating, isPercent),
            op = if (isRating) "worse than" else comparator(c.comparator),
            threshold = formatValue(c.errorThreshold, isRating, isPercent),
        )
    }

    private fun formatValue(raw: String, isRating: Boolean, isPercent: Boolean): String = when {
        isRating -> rating(raw)
        isPercent -> raw.toDoubleOrNull()?.let { "%.1f%%".format(it) } ?: raw
        else -> raw.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else raw } ?: raw
    }

    private fun rating(raw: String): String = when (raw.toDoubleOrNull()?.toInt()) {
        1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; 5 -> "E"; else -> raw
    }

    private fun comparator(c: String): String = when (c.uppercase()) {
        "GT" -> ">"; "LT" -> "<"; "EQ" -> "="; "NE" -> "≠"; else -> c
    }

    private fun prettify(key: String): String =
        key.removePrefix("new_").replace('_', ' ').replaceFirstChar { it.uppercase() }
}
