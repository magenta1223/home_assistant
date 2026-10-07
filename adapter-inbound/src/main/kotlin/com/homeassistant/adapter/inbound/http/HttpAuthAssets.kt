package com.homeassistant.adapter.inbound.http

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal const val HTTP_AUTH_SESSION_SCRIPT_ROUTE = "/assets/http-auth-session.js"

/** Shared by knowledge and conversation pages independently of any memory display. */
internal fun Route.httpAuthAssetRoutes() {
    get(HTTP_AUTH_SESSION_SCRIPT_ROUTE) {
        val script = requireNotNull(HttpAuthAssets::class.java.getResource("/http-auth-session.js"))
        call.respondText(script.readText(), ContentType.Application.JavaScript)
    }
}

private object HttpAuthAssets
