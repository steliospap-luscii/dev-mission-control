package com.devhub.sonar

import com.devhub.config.SonarConfig
import com.devhub.core.QualityReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class SonarClient(private val cfg: SonarConfig, token: String) : AutoCloseable {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, "devhub/0.1")
        }
        expectSuccess = true
    }

    private val metricKeys = listOf(
        "coverage", "new_coverage", "tests",
        "new_code_smells", "code_smells",
        "security_hotspots", "bugs", "vulnerabilities",
        "reliability_rating", "security_rating", "sqale_rating",
        "ncloc", "duplicated_lines_density",
    ).joinToString(",")

    /** One report per configured project. Failures degrade to a NONE-gate stub, never throw. */
    suspend fun fetchAll(): List<QualityReport> =
        cfg.projectKeys.map { key -> runCatching { fetch(key) }.getOrElse { stub(key, it) } }

    private suspend fun fetch(projectKey: String): QualityReport {
        // project_status is the one endpoint analysis-scoped tokens can always read,
        // and it already carries the failing gate conditions — our primary source.
        val gate: ProjectStatusResponse = http.get("${cfg.baseUrl}/api/qualitygates/project_status") {
            parameter("projectKey", projectKey)
        }.body()

        val failing = gate.projectStatus.conditions
            .filter { it.status == "ERROR" }
            .map { SonarFormat.toCondition(it) }

        // measures/component requires "Browse" permission; many tokens lack it
        // (SonarCloud masks that as 404). So enrich best-effort, never fail on it.
        val c = runCatching {
            http.get("${cfg.baseUrl}/api/measures/component") {
                parameter("component", projectKey)
                parameter("metricKeys", metricKeys)
            }.body<MeasuresResponse>().component
        }.getOrNull()

        return QualityReport(
            projectKey = projectKey,
            projectName = c?.name?.ifBlank { projectKey } ?: projectKey,
            gateStatus = gate.projectStatus.status,
            failingConditions = failing,
            coverage = c?.metric("coverage")?.toDoubleOrNull(),
            coverageDelta = null, // filled in by Poller from the seen-state store
            newCoverage = c?.metric("new_coverage")?.toDoubleOrNull(),
            tests = c?.metric("tests")?.toDoubleOrNull()?.toInt(),
            newCodeSmells = c?.metric("new_code_smells")?.toDoubleOrNull()?.toInt(),
            codeSmells = c?.metric("code_smells")?.toDoubleOrNull()?.toInt(),
            securityHotspots = c?.metric("security_hotspots")?.toDoubleOrNull()?.toInt(),
            bugs = c?.metric("bugs")?.toDoubleOrNull()?.toInt(),
            vulnerabilities = c?.metric("vulnerabilities")?.toDoubleOrNull()?.toInt(),
            reliabilityRating = c?.metric("reliability_rating")?.toDoubleOrNull()?.toInt(),
            securityRating = c?.metric("security_rating")?.toDoubleOrNull()?.toInt(),
            maintainabilityRating = c?.metric("sqale_rating")?.toDoubleOrNull()?.toInt(),
            ncloc = c?.metric("ncloc")?.toDoubleOrNull()?.toInt(),
            duplication = c?.metric("duplicated_lines_density")?.toDoubleOrNull(),
            url = "${cfg.baseUrl}/dashboard?id=$projectKey",
        )
    }

    private fun stub(projectKey: String, e: Throwable) = QualityReport(
        projectKey = projectKey,
        projectName = projectKey,
        gateStatus = "NONE",
        failingConditions = emptyList(),
        coverage = null, coverageDelta = null, newCodeSmells = null,
        securityHotspots = null, bugs = null, vulnerabilities = null,
        url = "${cfg.baseUrl}/dashboard?id=$projectKey",
    ).also { System.err.println("sonar[$projectKey]: ${e.message}") }

    override fun close() = http.close()
}
