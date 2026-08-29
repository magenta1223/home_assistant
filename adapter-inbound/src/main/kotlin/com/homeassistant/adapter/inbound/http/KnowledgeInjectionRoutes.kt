package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.inbound.text.PlainTextSourceParser
import com.homeassistant.application.port.input.memory.analysis.ConflictingSourceAudienceException
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.InvalidMemoryAudienceException
import com.homeassistant.application.port.input.memory.analysis.InvalidKnowledgeReferenceException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.Base64

internal fun Route.knowledgePageRoute() {
    get(AppConfig.ROUTE_KNOWLEDGE_PAGE) {
        val html = requireNotNull(javaClass.getResource("/knowledge.html")) {
            "knowledge.html is missing"
        }.readText()
        call.respondText(html, ContentType.Text.Html)
    }
}

internal fun Route.knowledgeInjectionRoutes(
    memoryAnalysis: MemoryAnalysis,
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
                MemoryAccess.PUBLIC
            } else {
                MemoryAccess.restricted(request.allowedUserIds.map(::UserId))
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
                memoryAnalysis.execute(
                    MemoryAnalysisRequest(
                        userId = principal.userId.value,
                        source = source,
                        access = access,
                    ),
                ),
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
        } catch (error: InvalidMemoryAudienceException) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to error.message, "userIds" to error.userIds.sorted()),
            )
        } catch (error: InvalidKnowledgeReferenceException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to error.message))
        } catch (error: MemoryAnalysisUnavailableException) {
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
