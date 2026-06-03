package com.devhub.config

/**
 * Stores secrets in the macOS login Keychain via the `security` CLI so tokens
 * never touch config.json or the repo. Service is always "devhub"; the account
 * is the logical key (e.g. "github-token").
 */
object Keychain {
    private const val SERVICE = "devhub"

    const val GITHUB_TOKEN = "github-token"
    const val SONAR_TOKEN = "sonar-token"
    const val ANTHROPIC_TOKEN = "anthropic-token"

    fun get(account: String): String? {
        val proc = ProcessBuilder(
            "security", "find-generic-password", "-s", SERVICE, "-a", account, "-w",
        ).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        return if (code == 0 && out.isNotEmpty()) out else null
    }

    fun set(account: String, secret: String) {
        // -U updates if the item already exists instead of erroring.
        val proc = ProcessBuilder(
            "security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", secret,
        ).start()
        val code = proc.waitFor()
        check(code == 0) { "Failed to store '$account' in Keychain (exit $code)." }
    }

    fun delete(account: String): Boolean {
        val proc = ProcessBuilder(
            "security", "delete-generic-password", "-s", SERVICE, "-a", account,
        ).start()
        return proc.waitFor() == 0
    }

    fun has(account: String): Boolean = get(account) != null
}
