package com.homeassistant.pipeline

import com.homeassistant.commands.CommandExecutor
import com.homeassistant.context.ContextRetriever
import com.homeassistant.models.ChatResponse
import com.homeassistant.models.ChatRequest
import com.homeassistant.nlp.ClaudeClient
import com.homeassistant.session.SessionKey
import com.homeassistant.session.SessionManager
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ChatPipeline::class.java)

/**
 * Orchestrates the full NLP pipeline:
 * 1. Load session history
 * 2. analyzeIntent → ContextRetriever.retrieve
 * 3. chatSession (Claude)
 * 4. If result → executeCommand + resetSession
 */
class ChatPipeline(
    private val sessions: SessionManager,
    private val claude: ClaudeClient,
    private val contextRetriever: ContextRetriever,
    private val commandExecutor: CommandExecutor,
) {

    suspend fun process(req: ChatRequest): ChatResponse {
        val sessionKey = SessionKey(req.platform, req.conversationId)
        val history = sessions.getMessages(sessionKey)

        return try {
            // 1. Analyze intent to decide what DB context to fetch
            val intentAnalysis = claude.analyzeIntent(history, req.text)

            // 2. Retrieve relevant DB context
            val contextResults = contextRetriever.retrieve(intentAnalysis.contexts, req.userId)

            // 3. Run the chat session (Claude maps to command)
            val nlpResponse = claude.chatSession(history, req.text, contextResults)

            // 4. Update session history
            sessions.addMessage(sessionKey, "user", req.text)
            sessions.addMessage(sessionKey, "assistant", nlpResponse.text)

            // 5. If a command was identified, execute it
            if (nlpResponse.type == "result" && nlpResponse.command != null) {
                val result = commandExecutor.execute(nlpResponse.command, nlpResponse.params ?: "", req.userId)
                sessions.resetSession(sessionKey)
                ChatResponse(
                    type = "result",
                    text = result.text ?: nlpResponse.text,
                    sessionReset = true,
                )
            } else {
                ChatResponse(
                    type = nlpResponse.type,
                    text = nlpResponse.text,
                    sessionReset = false,
                )
            }
        } catch (e: Exception) {
            log.error("Pipeline error for ${req.platform}:${req.conversationId}", e)
            ChatResponse(
                type = "error",
                text = "죄송해요, 요청을 처리하는 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.",
                sessionReset = false,
            )
        }
    }
}
