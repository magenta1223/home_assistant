package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.identity.InvalidCredentialsException
import com.homeassistant.application.port.input.identity.LoginSession
import com.homeassistant.application.port.input.identity.UserAuthentication
import com.homeassistant.application.port.input.identity.UserNameTakenException
import com.homeassistant.domain.identity.Password
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The routes a person needs before having a session. */
internal fun Route.publicLoginRoutes(authentication: UserAuthentication) {
    post(HTTP_SIGNUP_ROUTE) {
        val request = call.receive<HttpCredentialsRequest>()
        try {
            val user = authentication.signUp(request.name, request.password())
            call.respond(HttpStatusCode.Created, HttpUserResponse(user.userId.value, user.displayName))
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid name or password")))
        } catch (error: UserNameTakenException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to error.message))
        }
    }
    post(HTTP_LOGIN_ROUTE) {
        val request = call.receive<HttpCredentialsRequest>()
        try {
            call.issueSessionCookie(authentication.login(request.name, request.password()))
            call.respond(HttpStatusCode.NoContent)
        } catch (_: IllegalArgumentException) {
            call.respondInvalidCredentials()
        } catch (_: InvalidCredentialsException) {
            call.respondInvalidCredentials()
        }
    }
    post(HTTP_LOGOUT_ROUTE) {
        call.request.cookies[HTTP_SESSION_COOKIE]?.let(authentication::logout)
        call.expireSessionCookie()
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Answers whether the caller is authenticated. */
internal fun Route.sessionStatusRoute() {
    get(HTTP_SESSION_ROUTE) {
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun ApplicationCall.respondInvalidCredentials() {
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "name or password is incorrect"))
}

private fun ApplicationCall.issueSessionCookie(session: LoginSession) {
    appendSessionCookie(session.id, session.lifetime.seconds)
}

private fun ApplicationCall.expireSessionCookie() {
    appendSessionCookie("", 0)
}

private fun ApplicationCall.appendSessionCookie(value: String, maxAgeSeconds: Long) {
    response.cookies.append(
        name = HTTP_SESSION_COOKIE,
        value = value,
        maxAge = maxAgeSeconds,
        path = "/",
        secure = true,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
}

@Serializable
private data class HttpCredentialsRequest(
    val name: String,
    val password: String,
) {
    fun password(): Password = Password(password)
}

@Serializable
private data class HttpUserResponse(
    val userId: String,
    val name: String,
)

internal const val HTTP_SESSION_ROUTE = "/api/auth/session"
internal const val HTTP_SIGNUP_ROUTE = "/api/auth/signup"
internal const val HTTP_LOGIN_ROUTE = "/api/auth/login"
internal const val HTTP_LOGOUT_ROUTE = "/api/auth/logout"
internal const val HTTP_SESSION_COOKIE = "__Host-home-second-brain-session"
