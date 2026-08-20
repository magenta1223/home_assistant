package com.homeassistant.app

import com.homeassistant.adapter.inbound.http.configureRoutes
import com.homeassistant.adapter.inbound.http.HttpApiKeyConfig
import com.homeassistant.app.services.ApplicationServicesFactory
import com.homeassistant.configuration.AppConfig
import com.homeassistant.common.json.JsonSerializer
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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
    embeddedServer(
        Netty,
        host = AppConfig.DEFAULT_HOST,
        port = AppConfig.DEFAULT_PORT,
        module = Application::module,
    ).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(JsonSerializer.json)
    }

    install(CallLogging) {
        level = Level.INFO
    }

    val dbPath = environment
        .config
        .propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)
        ?.getString()
        ?: AppConfig.DEFAULT_DB_PATH

    log.info("Database: $dbPath")

    val httpApiKeys = HttpApiKeyConfig.fromEnv()
    val services = ApplicationServicesFactory.create(dbPath, httpApiKeys.values)
    try {
        services.start()
    } catch (failure: Exception) {
        services.close()
        throw failure
    }
    monitor.subscribe(ApplicationStopped) { services.close() }
    configureRoutes(
        services.memoryAnalysis,
        httpApiKeys,
        services.users,
        readiness = { services.isReady },
    )
}
