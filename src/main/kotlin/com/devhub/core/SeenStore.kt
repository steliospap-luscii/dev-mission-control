package com.devhub.core

import com.devhub.config.ConfigStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persists what we've already surfaced so we can (a) compute "what's new since
 * last poll" for notifications and (b) show SonarCloud coverage deltas locally.
 */
@Serializable
data class SeenState(
    val initialized: Boolean = false,   // false on the very first run → seed without notifying
    val seenPipelineRuns: Set<Long> = emptySet(),
    val seenPrUrls: Set<String> = emptySet(),
    val lastCoverage: Map<String, Double> = emptyMap(),
    val lastMetrics: Map<String, Double> = emptyMap(),  // Goals-tab KPI values, for deltas
    val pipelineCauses: Map<String, String> = emptyMap(), // "repo#runId" -> AI root cause (logs are immutable)
)

object SeenStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val path = ConfigStore.configDir.resolve("state.json")

    fun load(): SeenState =
        if (path.exists()) runCatching { json.decodeFromString<SeenState>(path.readText()) }.getOrDefault(SeenState())
        else SeenState()

    fun save(state: SeenState) {
        ConfigStore.configDir.createDirectories()
        path.writeText(json.encodeToString(state))
    }
}
