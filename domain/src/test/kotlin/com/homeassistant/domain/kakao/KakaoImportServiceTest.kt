package com.homeassistant.domain.kakao

import com.homeassistant.domain.db.tables.KakaoImportedMessageTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoImportServiceTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(KakaoImportedMessageTable) }
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `import stores only kakao messages and dedupes by fingerprint`() {
        val service = KakaoImportService(KakaoMessageRepository(db))
        val text = KakaoExportText(
            """
            [동훈] [오후 4:49] 따랑해
            [홍승민] [오후 5:38] 여기루 와용 ㅎㅎ
            """.trimIndent(),
        )

        val result = service.import(KakaoSourceFileName("2026-06-07.txt"), text)
        val repeated = service.import(KakaoSourceFileName("2026-06-07.txt"), text)

        assertEquals(2, result.importedMessageCount.value)
        assertEquals(2, result.messages.size)
        assertEquals(0, repeated.importedMessageCount.value)
    }
}
