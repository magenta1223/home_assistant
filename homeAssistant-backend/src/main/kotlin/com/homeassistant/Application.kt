package com.homeassistant

import com.homeassistant.commands.CommandExecutor
import com.homeassistant.context.ContextRetriever
import com.homeassistant.context.EmbeddingService
import com.homeassistant.db.DatabaseFactory
import com.homeassistant.nlp.ClaudeClient
import com.homeassistant.pipeline.ChatPipeline
import com.homeassistant.routes.configureRoutes
import com.homeassistant.session.SessionManager
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    // Content negotiation (JSON)
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    // Read config
    val dbPath = environment.config.propertyOrNull("homeassistant.dbPath")?.getString()
        ?: "../homeAssistant/db/homeAssistant.sqlite"
    val apiKey = environment.config.propertyOrNull("homeassistant.anthropicApiKey")?.getString()
        ?: System.getenv("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_API_KEY not set")

    // Initialize components
    DatabaseFactory.init(dbPath)
    val claudeClient = ClaudeClient(apiKey)
    val embeddingService = EmbeddingService()
    val contextRetriever = ContextRetriever(embeddingService)
    val commandExecutor = CommandExecutor(claudeClient)
    val sessionManager = SessionManager()
    val pipeline = ChatPipeline(sessionManager, claudeClient, contextRetriever, commandExecutor)

    // Wire routes
    configureRoutes(pipeline)
}
