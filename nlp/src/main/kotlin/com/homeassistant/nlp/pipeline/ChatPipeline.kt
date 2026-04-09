package com.homeassistant.nlp.pipeline

import com.homeassistant.core.tools.IToolExecutor
import com.homeassistant.core.commands.UserId
import com.homeassistant.core.models.ChatResponse
import com.homeassistant.core.models.ChatRequest
import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.AiClient
import com.homeassistant.core.nlp.ChatResponseType
import com.homeassistant.core.session.SessionKey
import com.homeassistant.core.session.SessionManager
import com.homeassistant.core.nlp.MessageRole
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ChatPipeline::class.java)

class ChatPipeline(
    private val sessions: SessionManager,
    private val aiClient: AiClient,
    private val toolExecutor: IToolExecutor,  // replaces ICommandExecutor
) : IChatPipeline {

    override suspend fun process(req: ChatRequest): ChatResponse {
        val sessionKey = SessionKey(req.platform, req.conversationId)
        val messages = sessions.getMessages(sessionKey).toMutableList()
        messages.add(Message.buildUserMessage(req.text))

        val nlpResponse = aiClient.chatSession(messages)

        return when (nlpResponse.type) {
            ChatResponseType.TOOL_CALL -> {
                val result = toolExecutor.execute(nlpResponse.toolCall!!, UserId(req.userId))
                sessions.addMessage(sessionKey, MessageRole.USER, req.text)
                messages.add(Message(MessageRole.TOOL_RESULT, result.value))
                val finalResponse = aiClient.chatSession(messages.toList())
                sessions.addMessage(sessionKey, MessageRole.ASSISTANT, finalResponse.text)
                sessions.resetSession(sessionKey)
                ChatResponse(type = ChatResponseType.RESULT.value, text = finalResponse.text, sessionReset = true)
            }
            else -> {
                sessions.addMessage(sessionKey, MessageRole.USER, req.text)
                sessions.addMessage(sessionKey, MessageRole.ASSISTANT, nlpResponse.text)
                ChatResponse(type = nlpResponse.type.value, text = nlpResponse.text)
            }
        }
    }
}
