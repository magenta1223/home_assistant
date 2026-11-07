package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticPagesTest {
    @Test
    fun `pages and their script are served from the static folder`() = testApplication {
        application { configureRoutes(unusedAnalysis) }

        val page = client.get("/knowledge")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.contentType()!!.match(ContentType.Text.Html))
        val script = client.get("/assets/http-auth-session.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.contentType()!!.match(ContentType.Text.JavaScript))
    }

    @Test
    fun `classpath files outside the static folder are not served`() = testApplication {
        application { configureRoutes(unusedAnalysis) }

        for (path in listOf(
            "/kakao/korean-timestamp-export.txt",
            "/com/homeassistant/adapter/inbound/http/AppRoutesKt.class",
            "/assets/%2E%2E/%2E%2E/kakao/korean-timestamp-export.txt",
        )) {
            assertFalse(client.get(path).status.isSuccess(), path)
        }
    }

    private val unusedAnalysis = object : EpisodeAnalysis {
        override suspend fun execute(request: EpisodeAnalysisRequest) = error("unused")
    }
}
