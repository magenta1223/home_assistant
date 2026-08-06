package com.homeassistant.adapter.inbound.http

import com.homeassistant.configuration.AppConfig
import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.application.topicanalysis.review.TopicAnalysisReviewNotFoundException
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

internal fun Route.kakaoTopicAnalysisRoutes(
    topicAnalysis: TopicAnalysisUseCase,
    saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
) {
    post(AppConfig.ROUTE_KAKAO_IMPORT_ANALYZE) {
        val request = call.receive<KakaoImportAnalyzeRequest>()
        val sourceName = request.sourceName
        if (sourceName.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceName is required"))
            return@post
        }
        val text = request.text
        if (text.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text is required"))
            return@post
        }
        val userId = request.userId
        if (userId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
            return@post
        }

        try {
            call.respond(
                HttpStatusCode.OK,
                topicAnalysis.execute(
                    TopicAnalysisRequest(
                        userId = userId,
                        source = KakaoExportParser.parse(sourceName, text),
                    ),
                ),
            )
        } catch (_: DuplicateSourceRecordsException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "all Kakao messages have already been analyzed"))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }

    post(AppConfig.ROUTE_KAKAO_IMPORT_SAVE) {
        val request = call.receive<TopicAnalysisSaveRequest>()
        if (request.previewId.isBlank() || request.userId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "previewId and userId are required"))
            return@post
        }

        try {
            call.respond(HttpStatusCode.OK, saveAnalyzedTopics.saveAll(request))
        } catch (_: TopicAnalysisReviewNotFoundException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "preview not found"))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }
}

@Serializable
private data class KakaoImportAnalyzeRequest(
    val userId: String? = null,
    val sourceName: String? = null,
    val text: String? = null,
)
