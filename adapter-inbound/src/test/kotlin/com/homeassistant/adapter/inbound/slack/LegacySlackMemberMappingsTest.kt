package com.homeassistant.adapter.inbound.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacySlackMemberMappingsTest {
    @Test
    fun `parses the former fixed mapping for one-time identity migration`() {
        val mappings = LegacySlackMemberMappings.fromJson(
            configuredTeamId = "team-1",
            json = """[{"teamId":"team-1","slackUserId":"slack-1","userId":"member-1"}]""",
        )

        assertEquals("team-1", mappings.single().teamId)
        assertEquals("slack-1", mappings.single().slackUserId)
        assertEquals("member-1", mappings.single().userId.value)
    }

    @Test
    fun `rejects a legacy mapping from another workspace`() {
        assertFailsWith<IllegalArgumentException> {
            LegacySlackMemberMappings.fromJson(
                configuredTeamId = "team-1",
                json = """[{"teamId":"other","slackUserId":"slack-1","userId":"member-1"}]""",
            )
        }
    }
}
