package com.homeassistant.llm.claude.conversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import com.homeassistant.llm.claude.cli.ClaudeCommandLine
import com.homeassistant.llm.claude.cli.ClaudeProcessRunner
import com.homeassistant.llm.claude.cli.ClaudeStreamJson
import com.homeassistant.llm.claude.cli.SystemClaudeProcessRunner
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class ClaudeConversationException(val category: String) : RuntimeException(category)

/**
 * Keeps each conversation as a persisted Claude Code session. A conversation id is a session UUID chosen here: its
 * first turn creates the session with `--session-id`, and later turns load its history with `--resume`.
 */
internal class ClaudeConversationGateway(
    private val config: ClaudeConversationConfig,
    private val runner: ClaudeProcessRunner = SystemClaudeProcessRunner,
) : ConversationGateway {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unstartedConversations = ConcurrentHashMap.newKeySet<String>()
    private val activeTurns = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)

    override fun begin(): Result<ConversationId> {
        if (closed.get()) return Result.failure(ClaudeConversationException("CLOSED"))
        val conversationId = UUID.randomUUID().toString()
        unstartedConversations += conversationId
        return Result.success(ConversationId(conversationId))
    }

    override fun continueConversation(conversationId: ConversationId, prompt: String): ConversationReply =
        execute(conversationId, prompt).fold(
            onSuccess = ConversationReply::Success,
            onFailure = { error ->
                log.warn("Conversation turn failed category={}", error.message ?: error.javaClass.simpleName)
                ConversationReply.Failure
            },
        )

    override fun end(conversationId: ConversationId) {
        unstartedConversations.remove(conversationId.value)
    }

    override fun close() {
        closed.set(true)
        unstartedConversations.clear()
    }

    internal fun execute(conversationId: ConversationId, prompt: String): Result<String> {
        val id = conversationId.value
        return try {
            if (closed.get()) throw ClaudeConversationException("CLOSED")
            if (!SESSION_ID_PATTERN.matches(id)) throw ClaudeConversationException("INVALID_CONVERSATION_ID")
            if (prompt.isBlank()) throw ClaudeConversationException("EMPTY_PROMPT")
            if (!activeTurns.add(id)) throw ClaudeConversationException("CONVERSATION_BUSY")
            try {
                Result.success(runTurn(id, prompt))
            } finally {
                activeTurns.remove(id)
            }
        } catch (error: ClaudeConversationException) {
            Result.failure(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(ClaudeConversationException("INTERRUPTED"))
        } catch (_: Exception) {
            Result.failure(ClaudeConversationException("CLI_FAILURE"))
        }
    }

    private fun runTurn(id: String, prompt: String): String {
        val startedAt = System.nanoTime()
        // An id unknown to this process (for example after a restart) belongs to an already persisted session.
        val session = if (id in unstartedConversations) listOf("--session-id", id) else listOf("--resume", id)
        val command = ClaudeCommandLine.headless(
            executable = config.executable,
            model = config.model,
            effort = config.effort,
            systemPromptFile = config.systemPromptFile.toString(),
        ) + session
        val output = runner.run(command, config.workDir, ClaudeStreamJson.userMessage(prompt), config.timeout)
        if (output.timedOut) throw ClaudeConversationException("TIMEOUT")
        val result = ClaudeStreamJson.result(output.stdout) ?: throw ClaudeConversationException("NO_RESULT")
        if (result.isError) throw ClaudeConversationException("TURN_FAILED")
        val answer = result.text?.trim()?.takeIf(String::isNotEmpty)
            ?: throw ClaudeConversationException("EMPTY_ANSWER")
        unstartedConversations.remove(id)
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
