package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.inbound.text.PlainTextSourceParser
import com.homeassistant.application.port.inbound.memoryextraction.ConflictingSourceAudienceException
import com.homeassistant.application.port.inbound.memoryextraction.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidEpisodeAudienceException
import com.homeassistant.application.port.inbound.memoryextraction.InvalidKnowledgeReferenceException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisResult
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisUnavailableException
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.Base64

internal fun Route.knowledgeInjectionRoutes(
    episodeAnalysis: EpisodeAnalysis,
    users: UserRegistry,
) {
    get(AppConfig.ROUTE_KNOWLEDGE_USERS) {
        call.respond(
            KnowledgeUsersResponse(
                users.list().map { KnowledgeUserResponse(it.userId.value, it.displayName) },
            ),
        )
    }

    post(AppConfig.ROUTE_KNOWLEDGE_IMPORT_ANALYZE) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        val request = call.receive<KnowledgeImportRequest>()
        val sourceName = request.sourceName.trim()
        val text = request.text.trim()
        if (sourceName.isEmpty() || (text.isEmpty() && request.reference == null)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceName and text or reference are required"))
            return@post
        }

        val access = try {
            if (request.isPublic) {
                require(request.allowedUserIds.isEmpty()) { "PUBLIC must not include allowedUserIds" }
                AccessControl.PUBLIC
            } else {
                AccessControl.restricted(request.allowedUserIds.map(::UserId))
            }
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid audience")))
            return@post
        }

        val source = try {
            val reference = request.reference?.toSourceReference()
            when (request.sourceType) {
                KnowledgeSourceType.KAKAO -> {
                    require(reference == null) { "KAKAO does not accept PDF or image references" }
                    KakaoExportParser.parse(sourceName, text)
                }
                KnowledgeSourceType.TEXT -> {
                    val parsed = if (text.isEmpty()) {
                        SourceDocumentDraft(SourceDescriptor("text", sourceName), emptyList())
                    } else {
                        PlainTextSourceParser.parse(sourceName, text)
                    }
                    parsed.copy(reference = reference)
                }
            }
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid source data")))
            return@post
        }
        if (source.records.isEmpty() && source.reference == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no source records were found"))
            return@post
        }

        try {
            call.respond(
                HttpStatusCode.OK,
                episodeAnalysis.execute(
                    EpisodeAnalysisRequest(
                        userId = principal.userId.value,
                        source = source,
                        access = access,
                    ),
                ).toResponse(),
            )
        } catch (error: DuplicateSourceRecordsException) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "error" to "all source records have already been analyzed",
                    "alreadyAnalyzedRecordCount" to error.recordCount,
                ),
            )
        } catch (error: ConflictingSourceAudienceException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to error.message))
        } catch (error: InvalidEpisodeAudienceException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to error.message, "userIds" to error.userIds.sorted()),
            )
        } catch (error: InvalidKnowledgeReferenceException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to error.message))
        } catch (error: EpisodeAnalysisUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to error.message))
        } catch (_: UserAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "user access denied"))
        }
    }
}

@Serializable
internal data class KnowledgeImportRequest(
    val sourceType: KnowledgeSourceType,
    val sourceName: String,
    val isPublic: Boolean,
    val allowedUserIds: Set<String> = emptySet(),
    val text: String = "",
    val reference: KnowledgeReferenceRequest? = null,
)

@Serializable
internal data class KnowledgeReferenceRequest(
    val fileName: String,
    val mediaType: String,
    val contentBase64: String,
) {
    fun toSourceReference(): SourceReferenceDraft {
        require(contentBase64.length <= MAX_BASE64_LENGTH) { "reference must be 20MB or smaller" }
        val content = try {
            Base64.getDecoder().decode(contentBase64)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("reference contentBase64 is invalid", error)
        }
        val declaredMediaType = mediaType.substringBefore(';').trim().lowercase()
        val normalizedMediaType = declaredMediaType.takeIf { it in SUPPORTED_MEDIA_TYPES }
            ?: inferMediaType(fileName)
        require(normalizedMediaType in SUPPORTED_MEDIA_TYPES) {
            "only PDF, PNG, JPEG, and WebP references are supported"
        }
        return SourceReferenceDraft(fileName.trim(), normalizedMediaType, content)
    }

    private fun inferMediaType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> ""
    }

    private companion object {
        const val MAX_BASE64_LENGTH = ((SourceReferenceDraft.MAX_BYTES + 2) / 3) * 4
        val SUPPORTED_MEDIA_TYPES = setOf("application/pdf", "image/png", "image/jpeg", "image/webp")
    }
}

@Serializable
internal enum class KnowledgeSourceType { KAKAO, TEXT }

@Serializable
private data class KnowledgeUsersResponse(val users: List<KnowledgeUserResponse>)

@Serializable
private data class KnowledgeUserResponse(
    val userId: String,
    val name: String,
)

@Serializable
private data class KnowledgeImportResponse(
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val retriedRecordCount: Int,
    val alreadyAnalyzedRecordCount: Int,
    val visibility: Visibility,
    val allowedUserIds: Set<String>,
    val episodeCount: Int,
    val episodes: List<KnowledgeEpisodeResponse>,
)

@Serializable
private data class KnowledgeEpisodeResponse(
    val text: String,
    val evidenceRefs: List<Int>,
)

private fun EpisodeAnalysisResult.toResponse() = KnowledgeImportResponse(
    sourceType = sourceType,
    sourceName = sourceName,
    importedRecordCount = importedRecordCount,
    retriedRecordCount = retriedRecordCount,
    alreadyAnalyzedRecordCount = alreadyAnalyzedRecordCount,
    visibility = visibility,
    allowedUserIds = allowedUserIds,
    episodeCount = episodeCount,
    episodes = episodes.map { it.toResponse() },
)

private fun EpisodeProposal.toResponse() = KnowledgeEpisodeResponse(
    text = content,
    evidenceRefs = evidenceIds,
)
