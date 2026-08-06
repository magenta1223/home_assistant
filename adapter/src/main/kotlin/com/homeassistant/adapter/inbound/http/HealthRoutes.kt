package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.shared.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.healthRoutes() {
    get(AppConfig.ROUTE_HEALTH) {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}
