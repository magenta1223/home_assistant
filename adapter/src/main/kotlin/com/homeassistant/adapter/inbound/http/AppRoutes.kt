package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSourceUseCase
import com.homeassistant.application.topicanalysis.analyze.DuplicateKakaoMessagesException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisPreviewNotFoundException
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidatesUseCase
import com.homeassistant.adapter.shared.config.AppConfig
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.application.topicanswer.answer.TopicAnswerRequest
import com.homeassistant.application.topicanswer.answer.TopicAnswerUseCase
import com.homeassistant.application.topicanswer.answer.MemorySearchIndexUnavailableException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Application.configureRoutes(
    analyzeSource: AnalyzeSourceUseCase,
    saveTopicCandidates: SaveTopicCandidatesUseCase,
    topicAnswer: TopicAnswerUseCase? = null,
) {
    routing {
        get(AppConfig.ROUTE_HEALTH) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post(AppConfig.ROUTE_KAKAO_IMPORT_ANALYZE) {

            val req = call.receive<KakaoImportAnalyzeRequest>()
            val sourceName = req.sourceName
            if (sourceName.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceName is required"))
                return@post
            }

            val text = req.text
            if (text.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text is required"))
                return@post
            }
            val userId = req.userId
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
                return@post
            }

            try {
                val result = analyzeSource.execute(
                    TopicAnalysisRequest(
                        userId = userId,
                        sourceType = "kakao",
                        sourceName = sourceName,
                        text = text
                    )
                )
                call.respond(HttpStatusCode.OK, result)
            } catch (e: DuplicateKakaoMessagesException) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "all Kakao messages have already been analyzed"),
                )
            } catch (e: HouseholdAccessDeniedException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
            }
        }

        post(AppConfig.ROUTE_KAKAO_IMPORT_SAVE) {

            val req = call.receive<TopicAnalysisSaveRequest>()
            if (req.previewId.isBlank() || req.userId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "previewId and userId are required"),
                )
                return@post
            }

            try {
                val result = saveTopicCandidates.saveAll(req)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: TopicAnalysisPreviewNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "preview not found"))
            } catch (e: HouseholdAccessDeniedException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
            }
        }

        post(AppConfig.ROUTE_TOPIC_ANSWER) {
            if (topicAnswer == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "topic answer is not configured"))
                return@post
            }

            val req = call.receive<TopicAnswerRequest>()
            if (req.userId.isBlank() || req.question.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "userId and question are required"),
                )
                return@post
            }

            try {
                call.respond(HttpStatusCode.OK, topicAnswer.answer(req))
            } catch (e: MemorySearchIndexUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            } catch (e: HouseholdAccessDeniedException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
            }
        }

    }
}
/**
 * Request body accepted by the Kakao import preview endpoint.
 *
 * @property sourceName Source name associated with the raw text.
 * @property text Raw Kakao export text.
 */
@Serializable
private data class KakaoImportAnalyzeRequest(
    val userId: String? = null,
    val sourceName: String? = null,
    val text: String? = null,
)
