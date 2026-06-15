package com.homeassistant.app

import com.homeassistant.app.routes.configureRoutes
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.domain.db.DatabaseFactory
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageRepository
import com.homeassistant.nlp.analysis.TopicAnalysisRepository
import com.homeassistant.nlp.analysis.TopicAnalysisService
import com.homeassistant.nlp.backend.LmBackendFactory
import com.homeassistant.app.routes.KakaoImportAnalyzeService
import com.homeassistant.nlp.backend.AiProvider
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

    log.info("Database: $dbPath")

    val db = DatabaseFactory.init(dbPath)
    val analysisBackend = LmBackendFactory.create(AiProvider.valueOf(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "openrouter"))
    val kakaoTopicAnalysis = KakaoImportAnalyzeService(
        KakaoImportService(KakaoMessageRepository(db)),
        TopicAnalysisService(TopicAnalysisRepository(db), analysisBackend),
    )
    configureRoutes(kakaoTopicAnalysis)
}
