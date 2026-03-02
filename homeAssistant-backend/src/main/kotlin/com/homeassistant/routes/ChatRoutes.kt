package com.homeassistant.routes

import com.homeassistant.models.ChatRequest
import com.homeassistant.pipeline.ChatPipeline
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRoutes(pipeline: ChatPipeline) {
    routing {
        // Health check
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // Main chat endpoint
        post("/api/chat") {
            val req = call.receive<ChatRequest>()
            val response = pipeline.process(req)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
