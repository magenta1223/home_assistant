package com.homeassistant.llm.claude.chat

import com.homeassistant.llm.claude.cli.ClaudeCommandLine
import com.homeassistant.llm.claude.cli.ClaudeProcessRunner
import com.homeassistant.llm.claude.cli.ClaudeStreamJson
import com.homeassistant.llm.claude.cli.SystemClaudeProcessRunner
import com.homeassistant.llm.ChatClient
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import com.homeassistant.infrastructure.runtime.RuntimeState
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class ClaudeChatException(val category: String) : RuntimeException(category)

/**
 * Keeps each chat as a persisted Claude Code session. A chat id is a session UUID chosen here: its
 * first turn creates the session with `--session-id`, and later turns load its history with `--resume`.
 * It accepts chats only while running; stopping forgets the chats whose first turn has not run.
 */
internal class ClaudeChatClient(
    private val config: ClaudeChatConfig,
    private val runner: ClaudeProcessRunner = SystemClaudeProcessRunner,
) : AbstractRuntime(), ChatClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unstartedChats = ConcurrentHashMap.newKeySet<String>()
    private val activeTurns = ConcurrentHashMap.newKeySet<String>()
    override fun openChat(): Result<String> {
        if (state != RuntimeState.RUNNING) return Result.failure(ClaudeChatException("NOT_RUNNING"))
        val chatId = UUID.randomUUID().toString()
        unstartedChats += chatId
        return Result.success(chatId)
    }

    override fun closeChat(chatId: String) {
        unstartedChats.remove(chatId)
    }

    override fun onStart() = Unit

    override fun onStop() {
        unstartedChats.clear()
    }

    override fun send(chatId: String, prompt: String): Result<String> {
        return try {
            if (state != RuntimeState.RUNNING) throw ClaudeChatException("NOT_RUNNING")
            if (!SESSION_ID_PATTERN.matches(chatId)) throw ClaudeChatException("INVALID_CHAT_ID")
            if (prompt.isBlank()) throw ClaudeChatException("EMPTY_PROMPT")
            if (!activeTurns.add(chatId)) throw ClaudeChatException("CHAT_BUSY")
            try {
                Result.success(runTurn(chatId, prompt))
            } finally {
                activeTurns.remove(chatId)
            }
        } catch (error: ClaudeChatException) {
            Result.failure(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(ClaudeChatException("INTERRUPTED"))
        } catch (_: Exception) {
            Result.failure(ClaudeChatException("CLI_FAILURE"))
        }
    }

    private fun runTurn(id: String, prompt: String): String {
        val startedAt = System.nanoTime()
        // An id unknown to this process (for example after a restart) belongs to an already persisted session.
        val session = if (id in unstartedChats) listOf("--session-id", id) else listOf("--resume", id)
        val command = ClaudeCommandLine.headless(
            executable = config.executable,
            model = config.model,
            effort = config.effort,
            systemPromptFile = config.systemPromptFile.toString(),
        ) + session
        val output = runner.run(command, config.workDir, ClaudeStreamJson.userMessage(prompt), config.timeout)
        if (output.timedOut) throw ClaudeChatException("TIMEOUT")
        val result = ClaudeStreamJson.result(output.stdout) ?: throw ClaudeChatException("NO_RESULT")
        if (result.isError) throw ClaudeChatException("TURN_FAILED")
        val answer = result.text?.trim()?.takeIf(String::isNotEmpty)
            ?: throw ClaudeChatException("EMPTY_ANSWER")
        unstartedChats.remove(id)
        log.info(
            "Latency stage=claude-turn result=completed model={} elapsedMs={}",
            config.model,
            (System.nanoTime() - startedAt) / 1_000_000,
        )
        return answer
    }

    private companion object {
        val SESSION_ID_PATTERN =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    }
}
