package com.homeassistant.app.routes

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.nlcore.CoreMessages
import com.homeassistant.core.models.ChatRequest
import com.homeassistant.nlp.pipeline.IChatPipeline
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureRoutes(pipeline: IChatPipeline) {
    routing {
        get(AppConfig.ROUTE_HEALTH) {
            call.respond(HttpStatusCode.OK, mapOf("status" to CoreMessages.HEALTH_STATUS))
        }

        post(AppConfig.ROUTE_CHAT) {
            val req = call.receive<ChatRequest>()
            val response = pipeline.process(req)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
