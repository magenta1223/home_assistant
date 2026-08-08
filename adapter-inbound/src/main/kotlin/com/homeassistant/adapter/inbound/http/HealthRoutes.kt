package com.homeassistant.adapter.inbound.http

import com.homeassistant.configuration.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.healthRoutes(readiness: () -> Boolean) {
    get(AppConfig.ROUTE_HEALTH) {
        if (readiness()) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
        }
    }
}
