package com.homeassistant.nlp.pipeline

import com.homeassistant.core.commands.CommandName
import com.homeassistant.core.commands.CommandParams
import com.homeassistant.core.commands.ICommandExecutor
import com.homeassistant.core.commands.UserId
import com.homeassistant.core.models.ChatResponse
import com.homeassistant.core.models.ChatRequest
import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.AiClient
import com.homeassistant.core.nlp.ChatResponseType
import com.homeassistant.core.session.SessionKey
import com.homeassistant.core.session.SessionManager
import com.homeassistant.core.nlp.CoreMessages
import com.homeassistant.core.nlp.MessageRole
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ChatPipeline::class.java)

class ChatPipeline(
    private val sessions: SessionManager,
    private val aiClient: AiClient,
    private val commandExecutor: ICommandExecutor,
) : IChatPipeline {

    override suspend fun process(req: ChatRequest):ChatResponse {
        log.info("Pipeline start [${req.platform}:${req.conversationId}] text='${req.text.take(100)}'")
        val sessionKey = SessionKey(req.platform, req.conversationId)
        val messages = sessions
            .getMessages(sessionKey)
            .toMutableList()
            .apply {
                add(Message.buildUserMessage(req.text))
            }

        return try {
            val nlpResponse = aiClient.chatSession(messages).also {
                log.info(it.text)
            }

            sessions.addMessage(sessionKey, MessageRole.USER, req.text)
            sessions.addMessage(sessionKey, MessageRole.ASSISTANT, nlpResponse.text)

            if (nlpResponse.type == ChatResponseType.RESULT.value && nlpResponse.command != null) {
                val result = commandExecutor.execute(
                    CommandName(nlpResponse.command!!),
                    CommandParams(nlpResponse.params ?: ""),
                    UserId(req.userId),
                )
                sessions.resetSession(sessionKey)
                log.info("Pipeline done type=${nlpResponse.type} sessionReset=true")
                ChatResponse(
                    type = ChatResponseType.RESULT.value,
                    text = result.text ?: nlpResponse.text,
                    sessionReset = true,
                )
            } else {
                log.info("Pipeline done type=${nlpResponse.type} sessionReset=false")
                ChatResponse(
                    type = nlpResponse.type,
                    text = nlpResponse.text,
                    sessionReset = false,
                )
            }
        } catch (e: Exception) {
            log.error("Pipeline error for ${req.platform}:${req.conversationId}", e)
            ChatResponse(
                type = ChatResponseType.ERROR.value,
                text = CoreMessages.PIPELINE_ERROR,
                sessionReset = false,
            )
        }
    }
}
