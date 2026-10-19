package com.homeassistant.llm.claude.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeCommandLineTest {
    @Test
    fun `windows arguments keep embedded quotes and backslashes`() {
        assertEquals("--print", ClaudeCommandLine.windowsQuote("--print"))
        assertEquals("C:\\dir\\file.txt", ClaudeCommandLine.windowsQuote("C:\\dir\\file.txt"))
        assertEquals("\"\"", ClaudeCommandLine.windowsQuote(""))
        assertEquals("\"two words\"", ClaudeCommandLine.windowsQuote("two words"))
        assertEquals("\"{\\\"a\\\":\\\"x \\\\\\\"y\\\"}\"", ClaudeCommandLine.windowsQuote("{\"a\":\"x \\\"y\"}"))
        assertEquals("\"dir with space\\\\\"", ClaudeCommandLine.windowsQuote("dir with space\\"))
    }

    @Test
    fun `only windows arguments after the executable are quoted`() {
        val command = listOf("claude.exe", "--tools", "", "--json-schema", "{\"type\":\"object\"}")

        assertEquals(command, ClaudeCommandLine.platformArguments(command, osName = "Linux"))
        assertEquals(
            listOf("claude.exe", "--tools", "\"\"", "--json-schema", "\"{\\\"type\\\":\\\"object\\\"}\""),
            ClaudeCommandLine.platformArguments(command, osName = "Windows 11"),
        )
    }

    @Test
    fun `headless calls disable tools and customizations and use stream-json`() {
        val command = ClaudeCommandLine.headless("claude", "claude-opus-5-5", "high", "system.txt")

        assertEquals(
            listOf(
                "claude", "--print", "--safe-mode", "--tools", "",
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--model", "claude-opus-5-5", "--effort", "high", "--system-prompt-file", "system.txt",
            ),
            command,
        )
    }
}
