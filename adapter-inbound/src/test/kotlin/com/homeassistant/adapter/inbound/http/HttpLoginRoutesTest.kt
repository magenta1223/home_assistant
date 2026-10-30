package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.output.identity.PasswordHash
import com.homeassistant.application.port.output.identity.PasswordHasher
import com.homeassistant.application.port.output.identity.StoredCredential
import com.homeassistant.application.port.output.identity.StoredSession
import com.homeassistant.application.port.output.identity.UserCredentialStore
import com.homeassistant.application.port.output.identity.UserRegistration
import com.homeassistant.application.port.output.identity.UserSessionStore
import com.homeassistant.application.usecase.identity.UserAuthenticationService
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.Password
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpLoginRoutesTest {
    @Test
    fun `the first user signs up and logs in with a secure session cookie`() = testApplication {
        application { configure() }

        assertContains(client.get(HTTP_SIGNUP_ROUTE).bodyAsText(), "\"open\": true")
        assertEquals(HttpStatusCode.Created, postCredentials(HTTP_SIGNUP_ROUTE, "김동훈").status)
        assertContains(client.get(HTTP_SIGNUP_ROUTE).bodyAsText(), "\"open\": false")

        val login = postCredentials(HTTP_LOGIN_ROUTE, "김동훈")

        assertEquals(HttpStatusCode.NoContent, login.status)
        val setCookie = login.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(setCookie.startsWith("$HTTP_SESSION_COOKIE="))
        assertContains(setCookie, "Max-Age=7776000")
        assertContains(setCookie, "Path=/")
        assertContains(setCookie, "Secure")
        assertContains(setCookie, "HttpOnly")
        assertContains(setCookie, "SameSite=Strict")
    }

    @Test
    fun `signup after the first user is forbidden`() = testApplication {
        application { configure() }
        postCredentials(HTTP_SIGNUP_ROUTE, "김동훈")

        assertEquals(HttpStatusCode.Forbidden, postCredentials(HTTP_SIGNUP_ROUTE, "홍승민").status)
    }

    @Test
    fun `a short password is rejected at signup`() = testApplication {
        application { configure() }

        assertEquals(HttpStatusCode.BadRequest, postCredentials(HTTP_SIGNUP_ROUTE, "김동훈", password = "short").status)
    }

    @Test
    fun `a wrong password gets no cookie`() = testApplication {
        application { configure() }
        postCredentials(HTTP_SIGNUP_ROUTE, "김동훈")

        for (password in listOf("wrong-password", "short")) {
            val login = postCredentials(HTTP_LOGIN_ROUTE, "김동훈", password)

            assertEquals(HttpStatusCode.Unauthorized, login.status)
            assertEquals(null, login.headers[HttpHeaders.SetCookie])
        }
    }

    @Test
    fun `requests without a credential are unauthorized`() = testApplication {
        application { configure() }

        assertEquals(HttpStatusCode.Unauthorized, client.get(HTTP_SESSION_ROUTE).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(AppConfig.ROUTE_KNOWLEDGE_USERS).status)
        assertEquals(HttpStatusCode.Unauthorized, postCredentials(HTTP_USERS_ROUTE, "홍승민").status)
    }

    @Test
    fun `the session cookie opens protected routes until logout`() = testApplication {
        application { configure() }
        postCredentials(HTTP_SIGNUP_ROUTE, "김동훈")
        val cookie = sessionCookie(postCredentials(HTTP_LOGIN_ROUTE, "김동훈"))

        assertEquals(HttpStatusCode.NoContent, client.get(HTTP_SESSION_ROUTE) { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.OK, client.get(AppConfig.ROUTE_KNOWLEDGE_USERS) { header(HttpHeaders.Cookie, cookie) }.status)

        val logout = client.post(HTTP_LOGOUT_ROUTE) { header(HttpHeaders.Cookie, cookie) }

        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertContains(logout.headers[HttpHeaders.SetCookie].orEmpty(), "Max-Age=0")
        assertEquals(HttpStatusCode.Unauthorized, client.get(HTTP_SESSION_ROUTE) { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `a logged in user adds a family member once`() = testApplication {
        application { configure() }
        postCredentials(HTTP_SIGNUP_ROUTE, "김동훈")
        val cookie = sessionCookie(postCredentials(HTTP_LOGIN_ROUTE, "김동훈"))

        assertEquals(HttpStatusCode.Created, postCredentials(HTTP_USERS_ROUTE, "홍승민") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.Conflict, postCredentials(HTTP_USERS_ROUTE, "홍승민") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.NoContent, postCredentials(HTTP_LOGIN_ROUTE, "홍승민").status)
    }

    @Test
    fun `machine clients keep using a bearer key and an unknown key is rejected`() = testApplication {
        application { configure() }

        assertEquals(HttpStatusCode.OK, client.get(AppConfig.ROUTE_KNOWLEDGE_USERS) { bearerAuth(API_KEY) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(AppConfig.ROUTE_KNOWLEDGE_USERS) { bearerAuth("unknown") }.status)
    }

    @Test
    fun `a bearer key of an unregistered user stops startup`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    configureRoutes(
                        memoryAnalysis = unusedMemoryAnalysis(),
                        httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_KEY) to UserId("not-registered")),
                    )
                }
                startApplication()
            }
        }
    }

    private fun Application.configure() {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        configureRoutes(
            memoryAnalysis = unusedMemoryAnalysis(),
            httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_KEY) to MACHINE.userId),
            users = object : UserRegistry {
                override fun list() = listOf(MACHINE)
            },
            authentication = UserAuthenticationService(InMemoryCredentials(), InMemorySessions(), PlainHasher),
        )
    }

    private suspend fun ApplicationTestBuilder.postCredentials(
        route: String,
        name: String,
        password: String = PASSWORD,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.post(route) {
        block()
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name","password":"$password"}""")
    }

    private fun sessionCookie(login: HttpResponse): String =
        login.headers[HttpHeaders.SetCookie].orEmpty().substringBefore(';')

    private fun unusedMemoryAnalysis() = object : MemoryAnalysis {
        override suspend fun execute(request: MemoryAnalysisRequest) = error("unused")
    }

    private object PlainHasher : PasswordHasher {
        override fun hash(password: Password) = PasswordHash("hashed:${password.value}")

        override fun matches(password: Password, hash: PasswordHash) = hash == hash(password)
    }

    private class InMemoryCredentials : UserCredentialStore {
        private val users = mutableMapOf<String, StoredCredential>()

        override fun hasUsers() = users.isNotEmpty()

        override fun registerFirst(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration {
            if (hasUsers()) return UserRegistration.USERS_ALREADY_EXIST
            return register(user, passwordHash)
        }

        override fun register(user: RegisteredUser, passwordHash: PasswordHash): UserRegistration {
            if (user.displayName in users) return UserRegistration.NAME_TAKEN
            users[user.displayName] = StoredCredential(user.userId, passwordHash)
            return UserRegistration.REGISTERED
        }

        override fun findByName(name: String) = users[name]
    }

    private class InMemorySessions : UserSessionStore {
        private val stored = mutableMapOf<String, StoredSession>()

        override fun save(session: StoredSession) {
            stored[session.idHash] = session
        }

        override fun findUser(idHash: String, now: Long): UserId? =
            stored[idHash]?.takeIf { it.expiresAt > now }?.userId

        override fun delete(idHash: String) {
            stored.remove(idHash)
        }

        override fun deleteExpired(now: Long) {
            stored.values.removeIf { it.expiresAt <= now }
        }
    }

    private companion object {
        const val PASSWORD = "correct-password"
        const val API_KEY = "machine-key"
        val MACHINE = RegisteredUser(UserId("kakao-exporter"), "카카오 내보내기")
    }
}
