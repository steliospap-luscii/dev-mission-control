package com.devhub.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class Config(
    /** Your GitHub login. Used for display; PR search uses `review-requested:@me`. */
    val githubLogin: String = "",
    /** "owner/repo" entries to watch for failed Actions runs (Platform panel). */
    val pipelineRepos: List<String> = emptyList(),
    /** Login of the Claude review App as it appears on PR reviews (GraphQL Bot login). */
    val claudeBotLogin: String = "claude",
    val sonar: SonarConfig = SonarConfig(),
    /** Poll interval in seconds. */
    val pollSeconds: Int = 120,
    /** Desktop notifications on new red signals. */
    val notifications: Boolean = true,
    val prFilter: PrFilterConfig = PrFilterConfig(),
    val progress: ProgressConfig = ProgressConfig(),
    val pipelines: PipelineConfig = PipelineConfig(),
) {
    /** Repos whose branches are counted on the Goals tab; defaults to the pipeline repos. */
    fun branchRepos(): List<String> = progress.branchRepos.ifEmpty { pipelineRepos }

    /** Repos analyzed for CI health on the Goals tab; defaults to the pipeline repos. */
    fun ciRepos(): List<String> = progress.ciRepos.ifEmpty { pipelineRepos }

    /** SonarCloud project sourcing the test/coverage KPIs; defaults to the first project. */
    fun progressSonarProject(): String =
        progress.sonarProject.ifBlank { sonar.projectKeys.firstOrNull().orEmpty() }

    fun validationIssues(): List<String> = buildList {
        if (githubLogin.isBlank()) add("githubLogin is empty — run `devhub config`.")
        if (sonar.projectKeys.isEmpty()) {
            add("sonar.projectKeys is empty — the Maintenance panel will be blank.")
        }
        if (pipelineRepos.isEmpty()) {
            add("pipelineRepos is empty — the Platform panel will be blank.")
        }
        if (pollSeconds < 30) add("pollSeconds < 30 risks hitting GitHub rate limits.")
    }
}

@Serializable
data class PrFilterConfig(
    val requireRequestedReviewer: Boolean = true,
    val requireClaudeBotReviewed: Boolean = true,
    val requireNotDraft: Boolean = true,
    val requireCiGreen: Boolean = true,
)

@Serializable
data class PipelineConfig(
    /** Cap the Platform tab to the N most-recent failures across watched repos. */
    val maxShown: Int = 5,
    /** Use Claude to summarize the real root cause of each failure. */
    val aiAnalysis: Boolean = true,
    /** Backend: "auto" (prefer local `claude` CLI, else API), "cli", "api", or "off". */
    val aiBackend: String = "auto",
    /** Model override; blank = backend default (CLI uses your Claude Code default). */
    val aiModel: String = "",
)

@Serializable
data class ProgressConfig(
    /** Repos to sum branch counts over (empty = use pipelineRepos). */
    val branchRepos: List<String> = emptyList(),
    /** Repos to analyze for CI health (empty = use pipelineRepos). */
    val ciRepos: List<String> = emptyList(),
    /** SonarCloud project key for the test/coverage KPIs (empty = first sonar project). */
    val sonarProject: String = "",
    /** Progress-bar targets for the coverage KPIs. */
    val coverageGoalPct: Double = 100.0,
    val newCoverageGoalPct: Double = 100.0,
    /** How many recent Actions runs per repo to analyze for CI health. */
    val ciWindow: Int = 100,
)

@Serializable
data class SonarConfig(
    val baseUrl: String = "https://sonarcloud.io",
    val organization: String = "",
    val projectKeys: List<String> = emptyList(),
)

object ConfigStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val configDir: Path = Path.of(System.getProperty("user.home"), ".config", "devhub")
    val configPath: Path = configDir.resolve("config.json")

    fun exists(): Boolean = configPath.exists()

    fun load(): Config {
        if (!configPath.exists()) return Config()
        return json.decodeFromString<Config>(configPath.readText())
    }

    fun save(config: Config) {
        configDir.createDirectories()
        configPath.writeText(json.encodeToString(config))
        // config.json holds no secrets, but tighten perms anyway.
        runCatching { Files.setPosixFilePermissions(configPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}
