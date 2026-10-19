package com.homeassistant.llm.claude.completion

import com.homeassistant.llm.claude.cli.ClaudeCommandLine
import com.homeassistant.llm.claude.cli.ClaudeProcessRunner
import com.homeassistant.llm.claude.cli.ClaudeStreamJson
import com.homeassistant.llm.claude.cli.SystemClaudeProcessRunner
import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText

/** Runs each completion as one ephemeral `claude --print` turn constrained by `--json-schema`. */
internal class ClaudeCliCompletionClient(
    private val executable: String = ClaudeCommandLine.executable(),
    private val model: String = DEFAULT_MODEL,
    private val effort: String = DEFAULT_EFFORT,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val runner: ClaudeProcessRunner = SystemClaudeProcessRunner,
) : CompletionClient {
    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
    ): String = completeWithImages(system, userMessage, outputSchema, emptyList())

    override suspend fun completeWithImages(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CompletionImage>,
    ): String = runInterruptible(Dispatchers.IO) {
        val schema = Json.parseToJsonElement(outputSchema).toString()
        val workingDirectory = Files.createTempDirectory("homeassistant-claude-")
        try {
            val systemPromptFile = workingDirectory.resolve("system-prompt.txt").apply { writeText(system) }
            val command = ClaudeCommandLine.headless(executable, model, effort, systemPromptFile.toString()) +
                listOf("--no-session-persistence", "--json-schema", schema)
            val output = runner.run(command, workingDirectory, ClaudeStreamJson.userMessage(userMessage, images), timeout)
            check(!output.timedOut) { "Claude completion timed out" }
            val result = ClaudeStreamJson.result(output.stdout)
                ?: error("Claude CLI did not return a result (exit ${output.exitCode})")
            check(!result.isError) { "Claude completion failed" }
            result.structuredOutput?.toString()
                ?: result.text?.takeIf(String::isNotBlank)
                ?: error("Claude CLI did not return its final response")
        } finally {
            Files.walk(workingDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_EFFORT = "high"
        val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
