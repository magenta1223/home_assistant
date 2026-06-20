package com.homeassistant.app.routes

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.kakao.KakaoExportText
import com.homeassistant.domain.kakao.KakaoSourceFileName
import com.homeassistant.nlp.analysis.TopicCandidate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

fun Application.configureRoutes(
    kakaoImportAnalyze: KakaoImportAnalyzeUseCase? = null,
) {
    routing {
        get(AppConfig.ROUTE_HEALTH) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post(AppConfig.ROUTE_KAKAO_IMPORT_ANALYZE) {
            if (kakaoImportAnalyze == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "kakao topic analysis is not configured"))
                return@post
            }

            val req = call.receive<KakaoImportAnalyzeRequest>()
            val sourceFileName = req.fileName ?: req.filePath?.let { Path.of(it).fileName.toString() }
            if (sourceFileName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "fileName or filePath is required"))
                return@post
            }

            val text = req.text ?: req.filePath?.let { Files.readString(Path.of(it)) }
            if (text.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text or readable filePath is required"))
                return@post
            }

            val result = kakaoImportAnalyze.importAndAnalyze(KakaoSourceFileName(sourceFileName), KakaoExportText(text))
            call.respond(HttpStatusCode.OK, result.toResponse())
        }

        get(AppConfig.ROUTE_TEST_TOPIC_ANALYSIS_KAKAO_SMALL_SET) {
            if (kakaoImportAnalyze == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "kakao topic analysis is not configured"))
                return@get
            }

            val result = kakaoImportAnalyze.previewAnalysis(
                KakaoSourceFileName(TEST_TOPIC_ANALYSIS_KAKAO_FILE_NAME),
                KakaoExportText(TEST_TOPIC_ANALYSIS_KAKAO_TEXT),
            )
            call.respond(HttpStatusCode.OK, result.toResponse())
        }
    }
}

private const val TEST_TOPIC_ANALYSIS_KAKAO_FILE_NAME = "topic-analysis-small-kakao-2026-03-15.txt"

private val TEST_TOPIC_ANALYSIS_KAKAO_TEXT = """
홍승민 님과 카카오톡 대화
저장한 날짜 : 2026년 6월 15일 오전 6:43


2026년 3월 15일 오후 1:58
2026년 3월 15일 오후 1:58, 동훈 : 우리은행 1002266102280
2026년 3월 15일 오후 2:34, 홍승민 : 사진
2026년 3월 15일 오후 5:48, 홍승민 : 수자인 부동산에 현 세입자 이사일 & 시간 정해졌는지 확인 (중도금 연락하면서)

장박사 부동산에 집 나갔는지 확인 
2026년 3월 15일 오후 5:48, 홍승민 : 톡게시판 '공지': 수자인 부동산에 현 세입자 이사일 & 시간 정해졌는지 확인 (중도금 연락하면서)

장박사 부동산에 집 나갔는지 확인 
2026년 3월 15일 오후 7:55, 홍승민 : 사진
2026년 3월 15일 오후 7:57, 홍승민 : 관리사무소 질문 리스트 (승민)
1. 1층 이사 어떻게 하는지? (엘베 이용?)
2. 엘베 몇인승? 
3. 엘베 사용료 얼마?
4. 엘베 사용료 어떻게 납부?
5. 이삿날 공문 붙여주시는지?
6. 시스템에어컨 공사 계획. 공사 신고 및 이웃 동의 필요한가?

2026년 3월 16일 오전 7:20
2026년 3월 16일 오전 7:20, 홍승민 : 가는즁
2026년 3월 16일 오전 7:54, 홍승민 : 세잎
2026년 3월 16일 오전 7:54, 동훈 : 차 탔지
2026년 3월 16일 오전 7:54, 동훈 : 나는 아직 가는 중
2026년 3월 16일 오전 7:55, 동훈 : 오늘 엄청 막히네
2026년 3월 16일 오전 8:11, 홍승민 : 웅 ㅋㅋ 오늘 짐 많아서 차 필수~
2026년 3월 16일 오전 8:12, 홍승민 : 월욜크리 ㅠㅠ
2026년 3월 16일 오전 8:12, 동훈 : 웅 아직도 한 5분 남았다
2026년 3월 16일 오전 8:12, 동훈 : 평소보다 10분 이상 더 걸리네
2026년 3월 16일 오전 8:14, 홍승민 : 웅웅 월요일은 항상 그런거같아
2026년 3월 16일 오전 8:14, 홍승민 : 거기에 사고나면 +20분
2026년 3월 16일 오전 8:14, 홍승민 : 오늘두 엄청 막히길래 좀 쫄렸는데 중간에 사고 났더라구 그 다음은 뻥뻥 뚫려서 잘왔음ㅋㅋ
2026년 3월 16일 오전 8:15, 동훈 : 그렇구만
2026년 3월 16일 오전 8:15, 동훈 : 잘됐당 ㅎㅎ
2026년 3월 16일 오전 9:20, 홍승민 : 우웅 ㅎㅎ
2026년 3월 16일 오전 9:24, 동훈 : Done
2026년 3월 16일 오전 9:24, 동훈 : 1460에 사서 1480에 팔았다
2026년 3월 16일 오전 9:24, 동훈 : 다합쳐서 한 20만 벌었네용
2026년 3월 16일 오전 9:24, 홍승민 : 잘해써용
""".trimIndent()

@Serializable
private data class KakaoImportAnalyzeRequest(
    val fileName: String? = null,
    val filePath: String? = null,
    val text: String? = null,
)

@Serializable
private data class KakaoImportAnalyzeResponse(
    val importedMessageCount: Int,
    val topics: List<TopicCandidateResponse>,
)

@Serializable
private data class TopicCandidateResponse(
    val id: Int,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidenceMessageIds: List<Int>,
    val claims: List<TopicClaimResponse>,
    val status: String,
)

@Serializable
private data class TopicClaimResponse(
    val id: Int,
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: String,
    val evidenceMessageIds: List<Int>,
)

private fun KakaoImportAnalyzeResult.toResponse(): KakaoImportAnalyzeResponse =
    KakaoImportAnalyzeResponse(
        importedMessageCount = importedMessageCount.value,
        topics = topics.map { it.toResponse() },
    )

private fun TopicCandidate.toResponse(): TopicCandidateResponse =
    TopicCandidateResponse(
        id = id.value,
        title = title.value,
        summary = summary.value,
        memoryTypes = memoryTypes,
        domains = domains.map { it.value },
        evidenceMessageIds = evidenceRefs.map { it.value },
        claims = claims.map {
            TopicClaimResponse(
                id = it.id.value,
                text = it.text.value,
                subject = it.subject.value,
                memoryType = it.memoryType,
                certainty = it.certainty.name,
                evidenceMessageIds = it.evidenceRefs.map { ref -> ref.value },
            )
        },
        status = status.name,
    )
