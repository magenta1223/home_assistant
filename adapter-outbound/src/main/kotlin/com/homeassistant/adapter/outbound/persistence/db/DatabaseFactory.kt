package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConfig.JournalMode
import org.sqlite.SQLiteConfig.SynchronousMode
import org.sqlite.SQLiteConfig.TransactionMode

internal object DatabaseFactory {
    /**
     * Opens the SQLite database at [dbPath] for concurrent HTTP requests and workers, creates missing tables, and
     * returns its read and write connections.
     *
     * - WAL journal: readers keep reading while one writer commits, and the writer does not wait for readers.
     * - Synchronous NORMAL: the setting WAL is designed for; a commit survives an application crash and only the
     *   last commits can be lost on power failure.
     * - IMMEDIATE write transactions: every write transaction takes the write lock when it begins, so a transaction
     *   that reads and then writes never has to upgrade its lock, which fails at once in a deadlock no busy timeout
     *   can wait out.
     * - Read-only, deferred read transactions: they take no write lock, so they never queue behind a writer.
     * - Busy timeout: a transaction waits up to [BUSY_TIMEOUT_MILLIS] for the current writer instead of failing.
     */
    fun init(dbPath: String): SqliteDatabases {
        val url = "jdbc:sqlite:$dbPath"
        val writerConfig = writerConfig()
        val writer = Database.connect(getNewConnection = { writerConfig.createConnection(url) })
        transaction(writer) {
            SchemaUtils.createMissingTablesAndColumns(
                EpisodeTable,
                EpisodeEvidenceTable,
                EpisodeViewerTable,
                ComponentTable,
                ComponentExtractionTable,
                EpisodeEmbeddingTable,
                ComponentEmbeddingTable,
                GroupTable,
                GroupMembershipTable,
                SourceReferenceTable,
                SourceRecordTable,
                SourceRecordViewerTable,
                ComponentAssignmentOutboxTable,
                EpisodeAnalysisJobTable,
                EpisodeAnalysisJobRecordTable,
                EpisodeAnalysisJobViewerTable,
                ChatSessionTable,
                ActiveChatSessionTable,
                UserTable,
                UserCredentialTable,
                UserSessionTable,
                FinanceTransactionTable,
            )
        }
        val readerConfig = readerConfig()
        val reader = Database.connect(
            getNewConnection = { readerConfig.createConnection(url) },
            databaseConfig = DatabaseConfig { defaultReadOnly = true },
        )
        return SqliteDatabases(reader, writer)
    }

    private fun writerConfig() = SQLiteConfig().apply {
        setJournalMode(JournalMode.WAL)
        setSynchronous(SynchronousMode.NORMAL)
        transactionMode = TransactionMode.IMMEDIATE
        setBusyTimeout(BUSY_TIMEOUT_MILLIS)
    }

    private fun readerConfig() = SQLiteConfig().apply {
        setReadOnly(true)
        transactionMode = TransactionMode.DEFERRED
        setBusyTimeout(BUSY_TIMEOUT_MILLIS)
    }

    private const val BUSY_TIMEOUT_MILLIS = 5_000
}
