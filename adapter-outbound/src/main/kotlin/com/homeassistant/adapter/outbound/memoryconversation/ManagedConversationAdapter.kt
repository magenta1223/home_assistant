package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationThreadLifecycle
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnExecutor

interface ManagedConversationAdapter : ConversationThreadLifecycle, ConversationTurnExecutor, AutoCloseable {
    fun isAvailable(): Boolean

    fun startServer(): Boolean
}
