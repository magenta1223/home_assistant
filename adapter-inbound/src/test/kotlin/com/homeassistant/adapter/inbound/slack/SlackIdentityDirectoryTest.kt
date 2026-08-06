package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackIdentityDirectoryTest {
    @Test
    fun `resolves only a server configured user`() {
        val directory = SlackIdentityDirectoryFactory.fromJson(
            configuredTeamId = "T1",
            json = """[{"teamId":"T1","slackUserId":"U1","userId":"dad"}]""",
        )

        val principal = directory.resolve("T1", "U1")!!

        assertEquals("dad", principal.userId.value)
        assertNull(directory.resolve("T1", "U2"))
        assertNull(directory.resolve("T2", "U1"))
        assertTrue(
            directory.accessPolicy.isAuthorized(
                UserId("dad"),
            ),
        )
    }

    @Test
    fun `rejects duplicate or cross team mappings`() {
        assertFailsWith<IllegalArgumentException> {
            SlackIdentityDirectoryFactory.fromJson(
                "T1",
                """[
                    {"teamId":"T1","slackUserId":"U1","userId":"dad"},
                    {"teamId":"T1","slackUserId":"U1","userId":"other"}
                ]""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SlackIdentityDirectoryFactory.fromJson(
                "T1",
                """[{"teamId":"T2","slackUserId":"U1","userId":"dad"}]""",
            )
        }
    }
}
