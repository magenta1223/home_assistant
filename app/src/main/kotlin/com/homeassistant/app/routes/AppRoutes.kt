package com.homeassistant.app.routes

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanswer.TopicAnswerRequest
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndexUnavailableException
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

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

            val result = kakaoImportAnalyze.analyze(
                TopicAnalysisRequest(
                    sourceType = "kakao",
                    sourceName = sourceFileName,
                    text = text
                )
            )
            call.respond(HttpStatusCode.OK, result)
        }

        post(AppConfig.ROUTE_KAKAO_IMPORT_SAVE) {

            val req = call.receive<TopicAnalysisSaveRequest>()
            if (req.previewId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "previewId is required"))
                return@post
            }

            try {
                val result = kakaoImportAnalyze.saveAnalysis(req.previewId)
                call.respond(HttpStatusCode.OK, result)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "preview not found"))
            }
        }

        post(AppConfig.ROUTE_TOPIC_ANSWER) {
            if (topicAnswer == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "topic answer is not configured"))
                return@post
            }

            val req = call.receive<TopicAnswerRequest>()
            if (req.question.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question is required"))
                return@post
            }

            try {
                call.respond(HttpStatusCode.OK, topicAnswer.answer(req))
            } catch (e: TopicClaimSearchIndexUnavailableException) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            }
        }

    }
}

/**
 * Request body accepted by the Kakao import preview endpoint.
 *
 * @property fileName Optional source file name used when raw text is provided.
 * @property filePath Optional local path to read when text is not provided.
 * @property text Optional raw Kakao export text.
 */
@Serializable
private data class KakaoImportAnalyzeRequest(
    val fileName: String? = null,
    val filePath: String? = null,
    val text: String? = null,
)



/**
 * API response representation of a topic candidate.
 *
 * @property id Candidate id assigned by storage or preview generation.
 * @property title Short topic title.
 * @property summary Review-facing topic summary.
 * @property memoryTypes Memory categories represented by the topic.
 * @property domains Normalized domain tags attached to the topic.
 * @property evidenceRefs Source references that support the topic.
 * @property claims Evidence-backed claims grouped under the topic.
 * @property status Review state for the topic candidate.
 */
@Serializable
private data class TopicCandidateResponse(
    val id: Int,
    val title: String,
    val summary: String,
    val memoryTypes: List<MemoryType>,
    val domains: List<String>,
    val evidenceRefs: List<Int>,
    val claims: List<TopicClaimResponse>,
    val status: String,
)

/**
 * API response representation of an evidence-backed topic claim.
 *
 * @property id Claim id assigned by storage or preview generation.
 * @property text Claim text suitable for memory review.
 * @property subject Person, place, or concept the claim is about.
 * @property memoryType Memory category assigned to the claim.
 * @property certainty How directly source evidence supports the claim.
 * @property evidenceRefs Source references that support the claim.
 */
@Serializable
private data class TopicClaimResponse(
    val id: Int,
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: String,
    val evidenceRefs: List<Int>,
)


