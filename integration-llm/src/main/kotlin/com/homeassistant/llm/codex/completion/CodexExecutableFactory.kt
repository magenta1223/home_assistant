package com.homeassistant.llm.codex.completion

internal object CodexExecutableFactory {
    fun executable(osName: String = System.getProperty("os.name")): String {
        return if (osName.startsWith("Windows", ignoreCase = true)) "codex.cmd" else "codex"
    }
}