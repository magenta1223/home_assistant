package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnClient

interface ManagedConversationTurnClient : ConversationTurnClient, AutoCloseable {
    fun isAvailable(): Boolean

    fun startServer(): Boolean
}
