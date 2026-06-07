package com.homeassistant.app.routes

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.models.ChatRequest
import com.homeassistant.core.nlp.CoreMessages
import com.homeassistant.domain.kakao.KakaoExportText
import com.homeassistant.domain.kakao.KakaoSourceFileName
import com.homeassistant.nlp.pipeline.IChatPipeline
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
    pipeline: IChatPipeline,
    kakaoImportAnalyze: KakaoImportAnalyzeUseCase? = null,
) {
    routing {
        get(AppConfig.ROUTE_HEALTH) {
            call.respond(HttpStatusCode.OK, mapOf("status" to CoreMessages.HEALTH_STATUS))
        }

        post(AppConfig.ROUTE_CHAT) {
            val req = call.receive<ChatRequest>()
            val response = pipeline.process(req)
            call.respond(HttpStatusCode.OK, response)
        }

        post(AppConfig.ROUTE_KAKAO_IMPORT_ANALYZE) {
            val analyzer = kakaoImportAnalyze
            if (analyzer == null) {
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

            val result = analyzer.importAndAnalyze(KakaoSourceFileName(sourceFileName), KakaoExportText(text))
            call.respond(HttpStatusCode.OK, result.toResponse())
        }
    }
}

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
    val memoryTypes: List<String>,
    val domains: List<String>,
    val evidenceMessageIds: List<Int>,
    val status: String,
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
        memoryTypes = memoryTypes.map { it.name },
        domains = domains.map { it.value },
        evidenceMessageIds = evidenceRefs.map { it.value },
        status = status.name,
    )
