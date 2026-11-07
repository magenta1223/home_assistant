package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatReceipt
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptChange
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.StaleProcessingFailure
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatReceiptRepositoryTest {
    @Test
    fun `a request is claimed once and keeps its answer for retries`() = withReceipts { receipts, sessionId ->
        assertEquals(ChatReceipt(KEY, ChatRequestStatus.PROCESSING, createdAt = 100, updatedAt = 100),
            receipts.save(ChatReceiptChange.Claim(KEY, 100)))
        assertFailsWith<ChatReceiptConflict> { receipts.save(ChatReceiptChange.Claim(KEY, 101)) }

        receipts.save(ChatReceiptChange.AttachSession(KEY, sessionId, 103))
        val ready = receipts.save(ChatReceiptChange.AnswerReady(KEY, "answer", 104))

        val expected = ChatReceipt(KEY, ChatRequestStatus.ANSWER_READY, sessionId, "answer", createdAt = 100, updatedAt = 104)
        assertEquals(expected, ready)
        assertEquals(expected, receipts.findByKey(KEY))
        assertNull(receipts.findByKey(ChatRequestKey(USER_ID, "unknown")))
    }

    @Test
    fun `only receipts processing since the cutoff fail as stale`() = withReceipts { receipts, _ ->
        val stale = ChatRequestKey(USER_ID, "stale")
        val recent = ChatRequestKey(USER_ID, "recent")
        val answered = ChatRequestKey(USER_ID, "answered")
        receipts.save(ChatReceiptChange.Claim(stale, 100))
        receipts.save(ChatReceiptChange.Claim(recent, 201))
        receipts.save(ChatReceiptChange.Claim(answered, 100))
        receipts.save(ChatReceiptChange.AnswerReady(answered, "answer", 150))

        val failed = receipts.save(StaleProcessingFailure(before = 200, now = 300))

        val expected = ChatReceipt(stale, ChatRequestStatus.FAILED, createdAt = 100, updatedAt = 300)
        assertEquals(listOf(expected), failed)
        assertEquals(expected, receipts.findByKey(stale))
        assertEquals(ChatRequestStatus.PROCESSING, receipts.findByKey(recent)?.status)
        assertEquals(ChatRequestStatus.ANSWER_READY, receipts.findByKey(answered)?.status)
    }

    private fun withReceipts(block: (ChatReceiptRepository, Int) -> Unit) {
        val path = Files.createTempFile("chat-receipts", ".db")
        try {
            val stores = RepositoryFactory.create(path.toString())
            val session = stores.chatSessions.createAndActivate(USER_ID, ChatId("thread-1"), 102)
            block(stores.chatReceipts, session.id)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
        val KEY = ChatRequestKey(USER_ID, "request-1")
    }
}
