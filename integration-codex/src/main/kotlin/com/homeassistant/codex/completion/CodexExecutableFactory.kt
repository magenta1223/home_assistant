package com.homeassistant.codex.completion

object CodexExecutableFactory {
    fun get(osName: String = System.getProperty("os.name")): String {
        return if (osName.startsWith("Windows", ignoreCase = true)) "codex.cmd" else "codex"
    }
}