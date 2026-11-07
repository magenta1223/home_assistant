package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionLease
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatSessionRepositoryTest {
    @Test
    fun `leases the active conversation of its user`() {
        val databasePath = Files.createTempFile("episode-conversation", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).chatSessions

            store.createAndActivate(USER_ID, ChatId("thread-1"), 102)

            val lease = assertIs<ChatSessionLease.Active>(
                store.lease(USER_ID, 105, 600_000),
            )
            assertEquals(ChatId("thread-1"), lease.session.chatId)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `expires active sessions at the ten minute boundary`() {
        val databasePath = Files.createTempFile("episode-conversation-expiry", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).chatSessions
            store.createAndActivate(USER_ID, ChatId("expired-thread"), 100)

            val expired = store.expireIdle(100)

            assertEquals(listOf(ChatId("expired-thread")), expired.map { it.chatId })
            assertIs<ChatSessionLease.None>(store.lease(USER_ID, 101, 600_000))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `does not lease a thread to a different application user`() {
        val databasePath = Files.createTempFile("episode-conversation-owner", ".db")
        try {
            val store = RepositoryFactory.create(databasePath.toString()).chatSessions
            store.createAndActivate(USER_ID, ChatId("private-thread"), 100)
            val impostor = UserId("different-user")

            assertIs<ChatSessionLease.None>(store.lease(impostor, 101, 600_000))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
