package com.homeassistant.adapter.inbound.http

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpAuthenticationTest {
    @Test
    fun `api key config stores only hashed token keys`() {
        val users = HttpApiKeyConfig.fromJson(
            """[{"userId":"dad","token":"secret-token"}]""",
        )

        assertEquals(UserId("dad"), users[HttpApiKeyConfig.hash("secret-token")])
        assertEquals(null, users["secret-token"])
    }

    @Test
    fun `api key config rejects duplicate users and tokens`() {
        assertFailsWith<IllegalArgumentException> {
            HttpApiKeyConfig.fromJson(
                """[{"userId":"dad","token":"one"},{"userId":"dad","token":"two"}]""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HttpApiKeyConfig.fromJson(
                """[{"userId":"dad","token":"one"},{"userId":"mom","token":"one"}]""",
            )
        }
    }
}
