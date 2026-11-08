package com.homeassistant.adapter.outbound.persistence.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * One SQLite file opened twice: [reader] for transactions that only read and [writer] for every transaction that
 * writes. In WAL mode a reader never queues behind the writer, while writers stay serialized because each one begins
 * with `BEGIN IMMEDIATE`. A transaction that writes keeps its own reads on [writer].
 */
internal class SqliteDatabases(private val reader: Database, private val writer: Database) {
    /** Runs [statement] in a read-only transaction that sees the last committed state without waiting for a writer. */
    fun <T> read(statement: Transaction.() -> T): T = transaction(reader, statement)

    /** Runs [statement] in a transaction that holds the write lock from its first statement until it commits. */
    fun <T> write(statement: Transaction.() -> T): T = transaction(writer, statement)
}
