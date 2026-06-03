package com.devhub.sonar

import kotlinx.serialization.Serializable

@Serializable
data class ProjectStatusResponse(val projectStatus: ProjectStatus = ProjectStatus())

@Serializable
data class ProjectStatus(
    val status: String = "NONE",                 // OK | ERROR | NONE
    val conditions: List<Condition> = emptyList(),
)

@Serializable
data class Condition(
    val status: String = "",
    val metricKey: String = "",
    val actualValue: String = "",
    val comparator: String = "",
    val errorThreshold: String = "",
)

@Serializable
data class MeasuresResponse(val component: MeasuredComponent = MeasuredComponent())

@Serializable
data class MeasuredComponent(
    val key: String = "",
    val name: String = "",
    val measures: List<Measure> = emptyList(),
) {
    /** New-code metrics (new_*) report their value under period/periods, not `value`. */
    fun metric(key: String): String? = measures.firstOrNull { it.metric == key }?.let {
        it.value ?: it.period?.value ?: it.periods.firstOrNull()?.value
    }
}

@Serializable
data class Measure(
    val metric: String = "",
    val value: String? = null,
    val period: Period? = null,
    val periods: List<Period> = emptyList(),
)

@Serializable
data class Period(val value: String = "")
