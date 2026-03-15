package com.homeassistant

import com.homeassistant.commands.CommandExecutor
import com.homeassistant.constants.AppConfig
import com.homeassistant.constants.Env
import com.homeassistant.context.ContextRetriever
import com.homeassistant.context.EmbeddingService
import com.homeassistant.db.DatabaseFactory
import com.homeassistant.nlp.AiClient
import com.homeassistant.nlp.AiClientFactory
import com.homeassistant.pipeline.ChatPipeline
import com.homeassistant.pipeline.DummyChatPipeline
import com.homeassistant.pipeline.IChatPipeline
import com.homeassistant.routes.configureRoutes
import com.homeassistant.session.SessionManager
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("Application")

fun main() {
    System.setOut(java.io.PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(java.io.PrintStream(System.err, true, Charsets.UTF_8))
    log.info("Starting server on port ${AppConfig.DEFAULT_PORT}")
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

    // HTTP request/response logging
    install(CallLogging) {
        level = Level.INFO
    }

    // Read config
    val dbPath = environment.config.propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)?.getString()
        ?: AppConfig.DEFAULT_DB_PATH
    val aiProvider = Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "anthropic"
    val dummy = Env[AppConfig.ENV_VAR_USE_DUMMY_PIPELINE] == "true"

    log.info("Database: $dbPath")
    log.info("AI provider: $aiProvider")
    log.info("Pipeline: ${if (dummy) "DUMMY" else "CHAT"}")

    // Initialize components
    DatabaseFactory.init(dbPath)
    val aiClient: AiClient = AiClientFactory.create()
    val embeddingService = EmbeddingService()
    val contextRetriever = ContextRetriever(embeddingService)
    val commandExecutor = CommandExecutor(aiClient)
    val sessionManager = SessionManager()
    val pipeline: IChatPipeline =
        if (dummy) {
            DummyChatPipeline()
        } else {
            ChatPipeline(sessionManager, aiClient, contextRetriever, commandExecutor)
        }

    // Wire routes
    configureRoutes(pipeline)
}