package com.homeassistant.adapter.outbound.persistence.slackconversation

import com.homeassistant.application.slackconversation.handle.SlackMessageKey
import com.homeassistant.application.slackconversation.handle.SlackMessageReceiptStatus
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackMessageReceiptTable
import com.homeassistant.adapter.outbound.persistence.repo.slackconversation.SlackCodexSessionRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlackCodexSessionRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: SlackCodexSessionRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                SlackCodexSessionTable,
                SlackCodexActiveSessionTable,
                SlackMessageReceiptTable,
            )
        }
        repository = SlackCodexSessionRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `active session requires the exact persisted user and expires at ten minutes`() {
        val principal = SlackPrincipal("T1", "U1", com.homeassistant.domain.identity.UserId("dad"))
        repository.createAndActivate(
            principal,
            "119fa391-a538-7531-b719-c20d3d330bdc",
            now = 1_000,
        )

        assertNotNull(repository.active(principal, 600_999, 600_000))
        assertNull(
            repository.active(
                SlackPrincipal("T1", "U1", com.homeassistant.domain.identity.UserId("other")),
                1_001,
                600_000,
            ),
        )

        repository.createAndActivate(
            principal,
            "019fa391-a538-7531-b719-c20d3d330bdc",
            now = 1_000,
        )
        assertNull(repository.active(principal, 601_000, 600_000))
    }

    @Test
    fun `receipt completes only with a nonblank verified Slack response timestamp`() {
        val key = SlackMessageKey("D1", "100.1")
        assertNotNull(repository.claimMessage(key, 1_000))
        repository.markAnswerReady(key, "answer", 1_001)

        assertFailsWith<IllegalArgumentException> {
            repository.markCompleted(key, " ", 1_002)
        }
        assertEquals(SlackMessageReceiptStatus.ANSWER_READY, repository.receipt(key)?.status)

        repository.markCompleted(key, "200.2", 1_003)
        assertEquals(SlackMessageReceiptStatus.COMPLETED, repository.receipt(key)?.status)
        assertEquals("200.2", repository.receipt(key)?.responseTs)
        assertNull(repository.claimMessage(key, 1_004))
    }
}
