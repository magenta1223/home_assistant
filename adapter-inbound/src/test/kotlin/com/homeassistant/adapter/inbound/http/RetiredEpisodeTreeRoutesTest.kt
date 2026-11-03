package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.episode.EpisodeAnalysis
import com.homeassistant.application.port.inbound.episode.EpisodeAnalysisRequest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class RetiredEpisodeTreeRoutesTest {
    @Test
    fun `tree routes are retired but shared auth and remaining pages stay available`() = testApplication {
        application {
            configureRoutes(object : EpisodeAnalysis {
                override suspend fun execute(request: EpisodeAnalysisRequest) = error("unused")
            })
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/episodes").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/episode/tree").status)
        val script = client.get(HTTP_AUTH_SESSION_SCRIPT_ROUTE)
        assertEquals(HttpStatusCode.OK, script.status)
        assertContains(script.bodyAsText(), HTTP_LOGIN_ROUTE)
        assertFalse(script.bodyAsText().contains("localStorage"))
        for (path in listOf("/knowledge", "/conversation")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.OK, page.status)
            assertContains(page.bodyAsText(), HTTP_AUTH_SESSION_SCRIPT_ROUTE)
            assertFalse(page.bodyAsText().contains("href=\"/episodes\""))
        }
    }
}
