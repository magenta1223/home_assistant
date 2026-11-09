package com.homeassistant.llm.claude.cli

import com.homeassistant.llm.McpToolServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Builds Claude Code CLI invocations and their platform-specific argument encoding. */
internal object ClaudeCommandLine {
    fun executable(osName: String = System.getProperty("os.name")): String =
        if (isWindows(osName)) "claude.exe" else "claude"

    /**
     * Arguments shared by every headless call: one non-interactive turn over stream-json stdin/stdout and no
     * built-in tools. Without [toolServer] every user customization (CLAUDE.md, plugins, hooks, MCP servers) is off;
     * with it the agent may call that server's tools and nothing else.
     */
    fun headless(
        executable: String,
        model: String,
        effort: String,
        systemPromptFile: String,
        toolServer: McpToolServer? = null,
    ): List<String> = listOf(executable, "--print") +
        (toolServer?.let(::onlyServerTools) ?: listOf("--safe-mode")) +
        listOf(
            "--tools", "",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--verbose",
            "--model", model,
            "--effort", effort,
            "--system-prompt-file", systemPromptFile,
        )

    /**
     * Safe mode would also drop the server, so user settings, skills and slash commands are switched off one by one
     * instead, and only [server]'s tools are loaded and allowed.
     */
    private fun onlyServerTools(server: McpToolServer): List<String> = listOf(
        "--restricted",
        "--disable-slash-commands",
        "--mcp-config", mcpConfig(server),
        "--strict-mcp-config",
        "--allowedTools", "mcp__${server.name}",
    )

    private fun mcpConfig(server: McpToolServer): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject(server.name) {
                put("type", "http")
                put("url", server.url)
                putJsonObject("headers") { put("Authorization", "Bearer ${server.bearerToken}") }
            }
        }
    }.toString()

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
