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

    @Test
    fun `stores source records and deduplicates within source type`() {
        val repository = SourceRecordRepositoryImpl(db)
        val source = SourceDocumentDraft(
            source = SourceDescriptor("kakao", "2026-06-07.txt"),
            records = listOf(
                SourceRecordDraft("key-1", "동훈 | 오후 4:49 | 따랑해"),
                SourceRecordDraft("key-2", "홍승민 | 오후 5:38 | 여기루 와용 ㅎㅎ"),
            ),
        )
        val result = repository.saveAll(source.source, source.records)
        val repeated = repository.saveAll(source.source, source.records)

        assertEquals(2, result.size)
        assertEquals(result.map { it.id }, repeated.map { it.id })
        assertEquals(2, repository.findBySource(source.source).size)
        assertEquals(
            source.records.mapTo(mutableSetOf()) { it.deduplicationKey },
            repository.findExistingDeduplicationKeys(
                "kakao",
                source.records.mapTo(mutableSetOf()) { it.deduplicationKey },
            ),
        )
    }
}
