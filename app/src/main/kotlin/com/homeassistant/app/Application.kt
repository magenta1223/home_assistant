package com.homeassistant.app

import com.homeassistant.app.routes.configureRoutes
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.session.SessionManager
import com.homeassistant.nlp.NliPromptConfig
import com.homeassistant.nlp.pipeline.ChatPipeline
import com.homeassistant.nlp.models.AiClientFactory
import com.homeassistant.nlp.pipeline.NoOpToolExecutor
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.PrintStream

private val log = LoggerFactory.getLogger("Application")

fun main() {
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
    log.info("Starting server on port ${AppConfig.DEFAULT_PORT}")
    embeddedServer(Netty, port = AppConfig.DEFAULT_PORT, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    install(CallLogging) {
        level = Level.INFO
    }

    val dbPath = environment.config.propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)?.getString()
        ?: AppConfig.DEFAULT_DB_PATH
    val dummy = Env[AppConfig.ENV_VAR_USE_DUMMY_PIPELINE] == "true"

    log.info("Database: $dbPath")
    log.info("Pipeline: ${if (dummy) "DUMMY" else "CHAT"}")
// TODO: Add contextRetriever later
//    val registry = createDomainTableMetaRegistry()
//    val embeddingService = EmbeddingService(registry.allowedVecTables)
//    val contextRetriever = ContextRetriever(embeddingService, registry)
//    DatabaseFactory.init(dbPath, ALL_DOMAIN_TABLES)

    // TODO: Add commandExecutor later
//    val commandExecutor = CommandExecutor(aiClient, contextRetriever)

    val aiClient = AiClientFactory.create(NliPromptConfig(), tools = emptyList())
    val pipeline = ChatPipeline(SessionManager(), aiClient, NoOpToolExecutor())
    configureRoutes(pipeline)
}
