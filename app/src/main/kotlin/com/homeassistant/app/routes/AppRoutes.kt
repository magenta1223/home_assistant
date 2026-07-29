package com.homeassistant.app.routes

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.topicanswer.TopicAnswerRequest
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndexUnavailableException
import com.homeassistant.nlp.topicanalysis.api.DuplicateKakaoMessagesException
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisPreviewNotFoundException
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Application.configureRoutes(
    kakaoImportAnalyze: TopicAnalysisUseCase,
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
            val familyId = req.familyId
            if (userId.isNullOrBlank() || familyId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId and familyId are required"))
                return@post
            }

            try {
                val result = kakaoImportAnalyze.analyze(
                    TopicAnalysisRequest(
                        userId = userId,
                        familyId = familyId,
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
            if (req.previewId.isBlank() || req.userId.isBlank() || req.familyId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "previewId, userId, and familyId are required"),
                )
                return@post
            }

            try {
                val result = kakaoImportAnalyze.saveAnalysis(req)
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
            if (req.userId.isBlank() || req.familyId.isBlank() || req.question.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "userId, familyId, and question are required"),
                )
                return@post
            }

            try {
                call.respond(HttpStatusCode.OK, topicAnswer.answer(req))
            } catch (e: TopicClaimSearchIndexUnavailableException) {
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
    val familyId: String? = null,
    val sourceName: String? = null,
    val text: String? = null,
)
