package com.homeassistant

import com.homeassistant.commands.CommandExecutor
import com.homeassistant.constants.AppConfig
import com.homeassistant.context.ContextRetriever
import com.homeassistant.context.EmbeddingService
import com.homeassistant.db.DatabaseFactory
import com.homeassistant.nlp.AiClient
import com.homeassistant.nlp.AiClientFactory
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
    embeddedServer(Netty, port = AppConfig.DEFAULT_PORT, module = Application::module).start(wait = true)
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
    val dbPath = environment.config.propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)?.getString()
        ?: AppConfig.DEFAULT_DB_PATH

    // Initialize components
    DatabaseFactory.init(dbPath)
    val aiClient: AiClient = AiClientFactory.create()
    val embeddingService = EmbeddingService()
    val contextRetriever = ContextRetriever(embeddingService)
    val commandExecutor = CommandExecutor(aiClient)
    val sessionManager = SessionManager()
    val pipeline = ChatPipeline(sessionManager, aiClient, contextRetriever, commandExecutor)

    // Wire routes
    configureRoutes(pipeline)
}