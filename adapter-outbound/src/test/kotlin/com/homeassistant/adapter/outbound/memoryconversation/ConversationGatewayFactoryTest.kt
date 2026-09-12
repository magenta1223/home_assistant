package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.application.port.output.memory.conversation.ConversationReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationGatewayFactoryTest {
    @Test
    fun `factory exposes the application interface`() {
        val gateway: ConversationGateway = ConversationGatewayFactory.create()

        assertTrue(gateway.begin().isFailure)
    }

    @Test
    fun `no-op gateway calls are safe`() {
        val gateway = ConversationGatewayFactory.create()
        val conversationId = ConversationId("conversation-1")

        assertIs<ConversationReply.Failure>(
            gateway.continueConversation(conversationId, "question"),
        )
        assertEquals(Unit, gateway.end(conversationId))
        assertEquals(Unit, gateway.close())
    }
}
