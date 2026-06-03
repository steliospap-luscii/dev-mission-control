package com.devhub.ui

/** Opens a URL in the default browser (macOS `open`). */
object OpenUrl {
    fun open(url: String) {
        runCatching { ProcessBuilder("open", url).start() }
    }
}
