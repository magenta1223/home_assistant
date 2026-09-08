package com.homeassistant.adapter.inbound.http

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private const val HTTP_AUTH_NAME = "http-api"

data class HttpUserPrincipal(
    val userId: UserId,
) : Principal

@Serializable
private data class HttpMemberApiKeyConfig(
    val userId: String,
    val token: String,
)

object HttpApiKeyConfig {
    fun fromEnv(
        readEnv: (String) -> String? = { Env[it] },
    ): Map<String, UserId> {
        val raw = readEnv(AppConfig.ENV_VAR_HTTP_MEMBER_API_KEYS_JSON)
            ?.takeIf(String::isNotBlank)
            ?: return emptyMap()
        return fromJson(raw)
    }

    fun fromJson(raw: String): Map<String, UserId> {
        val records = JsonSerializer.json.decodeFromString<List<HttpMemberApiKeyConfig>>(raw)
        require(records.isNotEmpty()) { "HTTP_MEMBER_API_KEYS_JSON must not be empty" }

        val userIds = mutableSetOf<String>()
        val tokenHashes = mutableSetOf<String>()
        return records.associate { record ->
            val userId = UserId(record.userId)
            val token = record.token.trim()
            require(token.isNotBlank()) { "HTTP API token must not be blank" }
            require(userIds.add(userId.value)) { "Duplicate HTTP API userId: ${userId.value}" }
            val tokenHash = sha256(token)
            require(tokenHashes.add(tokenHash)) { "Duplicate HTTP API token" }
            tokenHash to userId
        }
    }

    internal fun hash(token: String): String = sha256(token)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

fun Application.configureHttpAuthentication(
    apiKeyUsers: Map<String, UserId>,
) {
    install(Authentication) {
        bearer(HTTP_AUTH_NAME) {
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.cookies[HTTP_AUTH_COOKIE]
                        ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
            }
            authenticate { credential ->
                apiKeyUsers[HttpApiKeyConfig.hash(credential.token)]
                    ?.let(::HttpUserPrincipal)
            }
        }
    }
}

internal fun Route.httpSessionRoutes() {
    route(HTTP_SESSION_ROUTE) {
        get {
            call.issueHttpAuthCookie()
            call.respond(HttpStatusCode.NoContent)
        }
        post {
            call.issueHttpAuthCookie()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.issueHttpAuthCookie() {
    val token = (request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
        ?.takeIf { it.authScheme.equals(AuthScheme.Bearer, ignoreCase = true) }
        ?.blob
        ?: request.cookies[HTTP_AUTH_COOKIE]
        ?: return
    response.cookies.append(
        name = HTTP_AUTH_COOKIE,
        value = token,
        maxAge = HTTP_AUTH_COOKIE_MAX_AGE_SECONDS,
        path = "/",
        secure = true,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
}

internal const val HTTP_AUTHENTICATION_NAME = HTTP_AUTH_NAME
internal const val HTTP_SESSION_ROUTE = "/api/auth/session"
internal const val HTTP_AUTH_COOKIE = "__Host-home-second-brain-token"
private const val HTTP_AUTH_COOKIE_MAX_AGE_SECONDS = 90L * 24 * 60 * 60
