package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.identity.UserAuthentication
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserId
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Principal
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private const val HTTP_API_KEY_AUTH_NAME = "http-api-key"
private const val HTTP_SESSION_AUTH_NAME = "http-session"

/** The authentication providers of a protected route: machines use API keys, people use session cookies. */
internal val HTTP_AUTHENTICATION_NAMES = arrayOf(HTTP_API_KEY_AUTH_NAME, HTTP_SESSION_AUTH_NAME)

data class HttpUserPrincipal(
    val userId: UserId,
) : Principal

@Serializable
private data class HttpMemberApiKeyConfig(
    val userId: String,
    val token: String,
)

/** The machine clients allowed to call the API with a Bearer key, keyed by the key's hash. */
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

/** Installs Bearer API key and session cookie authentication; a protected route accepts either. */
fun Application.configureHttpAuthentication(
    apiKeyUsers: Map<String, UserId>,
    authentication: UserAuthentication,
) {
    install(Authentication) {
        bearer(HTTP_API_KEY_AUTH_NAME) {
            authenticate { credential ->
                apiKeyUsers[HttpApiKeyConfig.hash(credential.token)]
                    ?.let(::HttpUserPrincipal)
            }
        }
        bearer(HTTP_SESSION_AUTH_NAME) {
            authHeader { call ->
                call.request.cookies[HTTP_SESSION_COOKIE]
                    ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
            }
            authenticate { credential ->
                authentication.resolveSession(credential.token)
                    ?.let(::HttpUserPrincipal)
            }
        }
    }
}
