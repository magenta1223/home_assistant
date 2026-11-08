package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
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
                override fun submit(request: EpisodeAnalysisRequest) = error("unused")
            })
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/episodes").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/episode/tree").status)
        val script = client.get("/assets/http-auth-session.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertContains(script.bodyAsText(), HTTP_LOGIN_ROUTE)
        assertFalse(script.bodyAsText().contains("localStorage"))
        for (path in listOf("/knowledge", "/conversation")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.OK, page.status)
            assertContains(page.bodyAsText(), "/assets/http-auth-session.js")
            assertFalse(page.bodyAsText().contains("href=\"/episodes\""))
        }
    }
}
