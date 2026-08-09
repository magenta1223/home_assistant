package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.inbound.text.PlainTextSourceParser
import com.homeassistant.application.port.input.memory.analysis.ConflictingSourceAudienceException
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.InvalidMemoryAudienceException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
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
    householdMembers: HouseholdMembers,
) {
    get(AppConfig.ROUTE_KNOWLEDGE_USERS) {
        call.respond(
            KnowledgeUsersResponse(
                householdMembers.list().map { KnowledgeUserResponse(it.userId.value, it.displayName) },
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
        if (sourceName.isEmpty() || text.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sourceName and text are required"))
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
            when (request.sourceType) {
                KnowledgeSourceType.KAKAO -> KakaoExportParser.parse(sourceName, text)
                KnowledgeSourceType.TEXT -> PlainTextSourceParser.parse(sourceName, text)
            }
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid source data")))
            return@post
        }
        if (source.records.isEmpty()) {
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
        } catch (error: MemoryAnalysisUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to error.message))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }
}

@Serializable
internal data class KnowledgeImportRequest(
    val sourceType: KnowledgeSourceType,
    val sourceName: String,
    val isPublic: Boolean,
    val allowedUserIds: Set<String> = emptySet(),
    val text: String,
)

@Serializable
internal enum class KnowledgeSourceType { KAKAO, TEXT }

@Serializable
private data class KnowledgeUsersResponse(val users: List<KnowledgeUserResponse>)

@Serializable
private data class KnowledgeUserResponse(
    val userId: String,
    val name: String,
)
