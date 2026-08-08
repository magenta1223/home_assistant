package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

internal fun Route.kakaoMemoryAnalysisRoutes(
    memoryAnalysis: MemoryAnalysis,
) {
    post(AppConfig.ROUTE_KAKAO_IMPORT_ANALYZE) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
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
        try {
            call.respond(
                HttpStatusCode.OK,
                memoryAnalysis.execute(
                    MemoryAnalysisRequest(
                        userId = principal.userId.value,
                        source = KakaoExportParser.parse(sourceName, text),
                    ),
                ),
            )
        } catch (error: DuplicateSourceRecordsException) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf(
                    "error" to "all Kakao messages have already been analyzed",
                    "alreadyAnalyzedRecordCount" to error.recordCount,
                ),
            )
        } catch (error: MemoryAnalysisUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to error.message))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }
}

@Serializable
private data class KakaoImportAnalyzeRequest(
    val sourceName: String? = null,
    val text: String? = null,
)
