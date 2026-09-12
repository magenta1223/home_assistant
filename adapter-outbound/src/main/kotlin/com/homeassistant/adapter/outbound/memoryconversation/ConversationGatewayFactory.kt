package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.codex.conversation.CodexAppServer
import com.homeassistant.codex.conversation.CodexAppServerFactory
import java.time.Duration

object ConversationGatewayFactory {
    fun create(): ConversationGateway = NoOpConversationGateway

    fun create(timeout: Duration): ConversationGateway? =
        CodexAppServerFactory.create(timeout)?.let(::CodexConversationGateway)

    internal fun create(appServer: CodexAppServer): ConversationGateway =
        CodexConversationGateway(appServer)
}
