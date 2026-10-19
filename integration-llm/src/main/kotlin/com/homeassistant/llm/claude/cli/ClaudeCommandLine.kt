package com.homeassistant.llm.claude.cli

/** Builds Claude Code CLI invocations and their platform-specific argument encoding. */
internal object ClaudeCommandLine {
    fun executable(osName: String = System.getProperty("os.name")): String =
        if (isWindows(osName)) "claude.exe" else "claude"

    /**
     * Arguments shared by every headless call: one non-interactive turn over stream-json stdin/stdout,
     * no built-in tools, and no user customizations (CLAUDE.md, plugins, hooks, MCP servers).
     */
    fun headless(
        executable: String,
        model: String,
        effort: String,
        systemPromptFile: String,
    ): List<String> = listOf(
        executable,
        "--print",
        "--safe-mode",
        "--tools", "",
        "--input-format", "stream-json",
        "--output-format", "stream-json",
        "--verbose",
        "--model", model,
        "--effort", effort,
        "--system-prompt-file", systemPromptFile,
    )

    /**
     * Java passes Windows arguments without escaping embedded quotes, which corrupts JSON arguments such as
     * `--json-schema`. Windows arguments are therefore quoted here with the MSVC command-line rules.
     */
    fun platformArguments(command: List<String>, osName: String = System.getProperty("os.name")): List<String> =
        if (isWindows(osName)) command.mapIndexed { index, arg -> if (index == 0) arg else windowsQuote(arg) } else command

    internal fun windowsQuote(argument: String): String {
        if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '"' }) return argument
        return buildString {
            append('"')
            var backslashes = 0
            for (char in argument) {
                when (char) {
                    '\\' -> backslashes++
                    '"' -> {
                        append("\\".repeat(backslashes * 2 + 1)).append('"')
                        backslashes = 0
                    }
                    else -> {
                        append("\\".repeat(backslashes)).append(char)
                        backslashes = 0
                    }
                }
            }
            append("\\".repeat(backslashes * 2)).append('"')
        }
    }

    private fun isWindows(osName: String): Boolean = osName.startsWith("Windows", ignoreCase = true)
}
