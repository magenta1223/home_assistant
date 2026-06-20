package com.homeassistant.app.routes

import com.homeassistant.domain.kakao.ImportedMessageCount
import com.homeassistant.domain.kakao.KakaoExportText
import com.homeassistant.domain.kakao.KakaoSourceFileName
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryClassification
import com.homeassistant.nlp.analysis.DomainTag
import com.homeassistant.nlp.analysis.ClaimCertainty
import com.homeassistant.nlp.analysis.ClaimSubject
import com.homeassistant.nlp.analysis.ClaimText
import com.homeassistant.nlp.analysis.SourceName
import com.homeassistant.nlp.analysis.SourceRecordRef
import com.homeassistant.nlp.analysis.SourceType
import com.homeassistant.nlp.analysis.TopicClaim
import com.homeassistant.nlp.analysis.TopicClaimId
import com.homeassistant.nlp.analysis.TopicCandidate
import com.homeassistant.nlp.analysis.TopicCandidateId
import com.homeassistant.nlp.analysis.TopicSummary
import com.homeassistant.nlp.analysis.TopicTitle
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class KakaoImportRoutesTest {
    @Test
    fun `import analyze route reads request text and returns pending topic`() = testApplication {
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "동훈은 애정 표현을 했다.")
        assertContains(response.bodyAsText(), "SEMANTIC")
        assertContains(response.bodyAsText(), "STATE")
        assertContains(response.bodyAsText(), "OBSERVED")
        assertContains(response.bodyAsText(), "PENDING")
        assertEquals(KakaoSourceFileName("2026-06-07.txt"), FakeAnalyzer.sourceFileName)
        assertEquals(KakaoExportText("[동훈] [오후 4:49] 따랑해"), FakeAnalyzer.text)
    }

    @Test
    fun `test topic analysis route analyzes bundled small kakao conversation`() = testApplication {
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.get("/api/test/topic-analysis/kakao-small-set")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(FakeAnalyzer.sourceFileName.value, "topic-analysis-small-kakao")
        assertContains(FakeAnalyzer.text.value, "2026년 3월 15일 오후 1:58, 동훈 : 우리은행 1002266102280")
        assertContains(FakeAnalyzer.text.value, "관리사무소 질문 리스트")
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "importedMessageCount")
        assertContains(response.bodyAsText(), "PENDING")
    }
}

private object FakeAnalyzer : KakaoImportAnalyzeUseCase {
    var sourceFileName = KakaoSourceFileName("")
    var text = KakaoExportText("")

    override suspend fun importAndAnalyze(
        sourceFileName: KakaoSourceFileName,
        text: KakaoExportText,
    ): KakaoImportAnalyzeResult {
        this.sourceFileName = sourceFileName
        this.text = text
        return KakaoImportAnalyzeResult(
            importedMessageCount = ImportedMessageCount(1),
            topics = listOf(
                TopicCandidate(
                    id = TopicCandidateId(7),
                    sourceType = SourceType("kakao"),
                    sourceName = SourceName(sourceFileName.value),
                    title = TopicTitle("관계 표현"),
                    summary = TopicSummary("애정 표현을 주고받았다."),
                    classifications = listOf(MemoryClassification.parse("SEMANTIC", "STATE")),
                    domains = listOf(DomainTag("relationship")),
                    evidenceRefs = listOf(SourceRecordRef(1)),
                    claims = listOf(
                        TopicClaim(
                            id = TopicClaimId(8),
                            text = ClaimText("동훈은 애정 표현을 했다."),
                            subject = ClaimSubject("동훈"),
                            classification = MemoryClassification.parse("SEMANTIC", "STATE"),
                            certainty = ClaimCertainty.OBSERVED,
                            evidenceRefs = listOf(SourceRecordRef(1)),
                        ),
                    ),
                    status = CandidateStatus.PENDING,
                ),
            ),
        )
    }

    override suspend fun previewAnalysis(
        sourceFileName: KakaoSourceFileName,
        text: KakaoExportText,
    ): KakaoImportAnalyzeResult {
        this.sourceFileName = sourceFileName
        this.text = text
        return importAndAnalyze(sourceFileName, text)
    }
}
