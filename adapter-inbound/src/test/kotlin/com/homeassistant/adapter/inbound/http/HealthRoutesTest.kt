package com.homeassistant.adapter.inbound.http

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `returns ok when managed runtimes are ready`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            routing { healthRoutes { true } }
        }

        assertEquals(HttpStatusCode.OK, client.get(AppConfig.ROUTE_HEALTH).status)
    }

    @Test
    fun `returns service unavailable when embedding runtime is not ready`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            routing { healthRoutes { false } }
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(AppConfig.ROUTE_HEALTH).status)
    }
}
