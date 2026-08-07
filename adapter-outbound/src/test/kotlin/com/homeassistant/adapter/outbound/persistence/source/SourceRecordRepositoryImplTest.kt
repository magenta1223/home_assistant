package com.homeassistant.adapter.outbound.persistence.source

import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class SourceRecordRepositoryImplTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(SourceRecordTable) }
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }
}
