package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackMessageReceiptTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LegacySlackSessionMigrationTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            exec(
                """
                CREATE TABLE slack_codex_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    team_id TEXT NOT NULL,
                    slack_user_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    family_id TEXT NOT NULL,
                    codex_thread_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_active_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            exec(
                "CREATE TABLE slack_codex_active_sessions " +
                    "(team_id TEXT NOT NULL, slack_user_id TEXT NOT NULL, session_id INTEGER NOT NULL)",
            )
            exec(
                """
                CREATE TABLE slack_message_receipts (
                    channel_id TEXT NOT NULL,
                    message_ts TEXT NOT NULL,
                    session_id INTEGER,
                    status TEXT NOT NULL,
                    answer_text TEXT,
                    response_ts TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            exec(
                "INSERT INTO slack_codex_sessions VALUES " +
                    "(7, 'T1', 'U1', 'dad', 'legacy-family', 'thread-7', 1000, 2000)",
            )
            exec("INSERT INTO slack_codex_active_sessions VALUES ('T1', 'U1', 7)")
            exec(
                "INSERT INTO slack_message_receipts VALUES " +
                    "('D1', '1.0', 7, 'ANSWER_READY', 'answer', NULL, 1000, 2000)",
            )
        }
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `removes family column while preserving Slack session state`() {
        transaction(db) {
            migrateLegacySlackSessionSchema()
            migrateLegacySlackSessionSchema()

            val columns = mutableSetOf<String>()
            exec("PRAGMA table_info('slack_codex_sessions')") { result ->
                while (result.next()) columns += result.getString("name")
            }
            assertFalse("family_id" in columns)

            val session = SlackCodexSessionTable.selectAll().single()
            assertEquals(7, session[SlackCodexSessionTable.id])
            assertEquals("dad", session[SlackCodexSessionTable.userId])
            assertEquals("thread-7", session[SlackCodexSessionTable.codexThreadId])
            assertEquals(7, SlackCodexActiveSessionTable.selectAll().single()[SlackCodexActiveSessionTable.sessionId])
            val receipt = SlackMessageReceiptTable.selectAll().single()
            assertEquals("ANSWER_READY", receipt[SlackMessageReceiptTable.status])
            assertEquals("answer", receipt[SlackMessageReceiptTable.answerText])
        }
    }
}
