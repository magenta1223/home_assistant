package com.homeassistant.domain.kakao

import com.homeassistant.domain.db.tables.KakaoImportedMessageTable
import com.homeassistant.domain.db.tables.TopicAnalysisPreviewTable
import com.homeassistant.domain.db.tables.TopicCandidateTable
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.NewTopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.domain.topicanalysis.TopicAnalysisResult
import com.homeassistant.domain.topicanalysis.TopicDraft
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

//class KakaoAnalysisPreviewServiceTest {
//    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
//    private lateinit var keepAlive: java.sql.Connection
//    private lateinit var db: Database
//    private lateinit var analyzer: StaticTopicAnalyzer
//    private lateinit var service: KakaoAnalysisPreviewService
//
//    @BeforeTest
//    fun setup() {
//        keepAlive = DriverManager.getConnection(dbUrl)
//        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
//        transaction(db) {
//            SchemaUtils.create(KakaoImportedMessageTable, TopicCandidateTable, TopicAnalysisPreviewTable)
//        }
//        analyzer = StaticTopicAnalyzer()
//        service = KakaoAnalysisPreviewService(
//            KakaoImportService(KakaoMessageRepository(db)),
//            analyzer,
//            TopicAnalysisRepository(db),
//            KakaoAnalysisPreviewRepository(db),
//        )
//    }
//
//    @AfterTest
//    fun teardown() {
//        keepAlive.close()
//    }
//
//    @Test
//    fun `preview stores draft with temporary refs and does not import messages`() = runBlocking {
//        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)
//
//        assertNotNull(preview.previewId)
//        assertEquals(2, preview.importedMessageCount)
//        val previewTopic: NewTopicCandidate = preview.topics.single()
//        assertEquals(listOf(1), preview.topics.single().evidenceRefs)
//        assertEquals(listOf(1), previewTopic.claims.single().evidenceRefs)
//        assertEquals(1, analyzer.calls)
//        assertEquals(0, countRows(KakaoImportedMessageTable.tableName))
//        assertEquals(0, countRows(TopicCandidateTable.tableName))
//        assertEquals(1, countRows(TopicAnalysisPreviewTable.tableName))
//    }
//
//    @Test
//    fun `save imports messages and persists topic candidates with stored message ids`() = runBlocking {
//        KakaoImportService(KakaoMessageRepository(db)).import("existing.txt", "[시스템] [오전 1:00] existing")
//        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)
//
//        val saved = service.savePreview(preview.previewId)
//
//        val savedTopic = saved.topics.single()
//        assertEquals(1, analyzer.calls)
//        assertEquals(3, countRows(KakaoImportedMessageTable.tableName))
//        assertEquals(1, countRows(TopicCandidateTable.tableName))
//        assertNotEquals(listOf(1), savedTopic.evidenceRefs)
//        assertEquals(savedTopic.evidenceRefs, savedTopic.claims.single().evidenceRefs)
//    }
//
//    @Test
//    fun `repeated save reuses existing topic`() = runBlocking {
//        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)
//
//        val first = service.savePreview(preview.previewId).topics.single()
//        val second = service.savePreview(preview.previewId).topics.single()
//
//        assertEquals(first.id, second.id)
//        assertEquals(1, countRows(TopicCandidateTable.tableName))
//    }
//
//    @Test
//    fun `missing preview id throws not found exception`() = runBlocking {
//        assertFailsWith<KakaoAnalysisPreviewNotFoundException> {
//            service.savePreview("missing")
//        }
//    }
//
//    private fun countRows(table: String): Int = transaction(db) {
//        var count = 0
//        exec("SELECT COUNT(*) FROM $table") { result ->
//            result.next()
//            count = result.getInt(1)
//        }
//        count
//    }
//}
//
//private val kakaoText = """
//    [동훈] [오후 4:49] 따랑해
//    [홍승민] [오후 5:38] 여기루 와용 ㅎㅎ
//""".trimIndent()
//
//private class StaticTopicAnalyzer : TopicAnalyzer {
//    var calls = 0
//
//    override suspend fun analyze(document: SourceDocument): TopicAnalysisResult {
//        calls += 1
//        val evidence = listOf(document.records.first())
//        return TopicAnalysisResult(
//            topics = listOf(
//                TopicDraft(
//                    title = "관계 표현",
//                    summary = "애정 표현을 주고받았다.",
//                    memoryTypes = listOf(com.homeassistant.core.memory.MemoryType.STATE),
//                    domains = listOf("relationship"),
//                    evidence = evidence,
//                    claims = listOf(
//                        NewTopicClaim(
//                            text = "동훈은 애정 표현을 했다.",
//                            subject = "동훈",
//                            memoryType = com.homeassistant.core.memory.MemoryType.STATE,
//                            certainty = com.homeassistant.domain.topicanalysis.ClaimCertainty.OBSERVED,
//                            evidence = evidence,
//                        ),
//                    ),
//                ),
//            ),
//        )
//    }
//}
