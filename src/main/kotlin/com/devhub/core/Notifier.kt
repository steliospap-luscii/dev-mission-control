package com.devhub.core

/** macOS desktop notifications. Prefers terminal-notifier if installed, else osascript. */
object Notifier {

    private val hasTerminalNotifier: Boolean by lazy {
        runCatching {
            ProcessBuilder("which", "terminal-notifier")
                .redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    fun notify(title: String, message: String, openUrl: String? = null) {
        runCatching {
            if (hasTerminalNotifier) {
                val cmd = mutableListOf(
                    "terminal-notifier", "-title", title, "-message", message, "-group", "devhub",
                )
                if (openUrl != null) { cmd += "-open"; cmd += openUrl }
                ProcessBuilder(cmd).start()
            } else {
                // Escape double quotes for AppleScript string literals.
                val t = title.replace("\"", "\\\"")
                val m = message.replace("\"", "\\\"")
                ProcessBuilder(
                    "osascript", "-e",
                    "display notification \"$m\" with title \"$t\"",
                ).start()
            }
        }
    }
}
