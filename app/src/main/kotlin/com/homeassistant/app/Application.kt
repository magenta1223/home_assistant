package com.homeassistant.app

import com.homeassistant.app.routes.configureRoutes
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.domain.db.DatabaseFactory
import com.homeassistant.domain.kakao.KakaoAnalysisPreviewRepository
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageRepository
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.nlp.topicanalysis.TopicAnalysisService
import com.homeassistant.nlp.backend.LmBackendFactory
import com.homeassistant.app.routes.KakaoImportAnalyzeService
import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.nlp.backend.AiProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
        json(JsonSerializer.json)
    }

    install(CallLogging) {
        level = Level.INFO
    }

    val dbPath = environment.config.propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)?.getString()
        ?: AppConfig.DEFAULT_DB_PATH

    log.info("Database: $dbPath")

    val db = DatabaseFactory.init(dbPath)
    val analysisBackend = LmBackendFactory.create(AiProvider.from(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: "openrouter"))
    val kakaoTopicAnalysis = KakaoImportAnalyzeService(
        KakaoImportService(KakaoMessageRepository(db)),
        TopicAnalysisService(analysisBackend),
        TopicAnalysisRepository(db),
        KakaoAnalysisPreviewRepository(db),
    )
    configureRoutes(kakaoTopicAnalysis)
}
