package com.homeassistant.app.slack

import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.core.identity.FamilyId
import com.homeassistant.core.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackIdentityDirectoryTest {
    @Test
    fun `resolves only the server configured immutable household scope`() {
        val directory = SlackIdentityDirectory.fromJson(
            configuredTeamId = "T1",
            json = """[{"teamId":"T1","slackUserId":"U1","userId":"dad","familyId":"family-1"}]""",
        )

        val principal = directory.resolve("T1", "U1")!!

        assertEquals("dad", principal.userId.value)
        assertEquals("family-1", principal.familyId.value)
        assertNull(directory.resolve("T1", "U2"))
        assertNull(directory.resolve("T2", "U1"))
        assertTrue(
            directory.accessPolicy.isAuthorized(
                HouseholdAccessScope(UserId("dad"), FamilyId("family-1")),
            ),
        )
    }

    @Test
    fun `rejects duplicate or cross team mappings`() {
        assertFailsWith<IllegalArgumentException> {
            SlackIdentityDirectory.fromJson(
                "T1",
                """[
                    {"teamId":"T1","slackUserId":"U1","userId":"dad","familyId":"family-1"},
                    {"teamId":"T1","slackUserId":"U1","userId":"other","familyId":"family-2"}
                ]""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SlackIdentityDirectory.fromJson(
                "T1",
                """[{"teamId":"T2","slackUserId":"U1","userId":"dad","familyId":"family-1"}]""",
            )
        }
    }
}
