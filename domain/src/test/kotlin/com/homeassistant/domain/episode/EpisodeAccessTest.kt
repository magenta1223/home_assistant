package com.homeassistant.domain.episode

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeAccessTest {
    @Test
    fun `public is visible to every requester`() {
        assertTrue(EpisodeAccess.PUBLIC.isVisibleTo(UserId("member-1")))
        assertTrue(EpisodeAccess.PUBLIC.isVisibleTo(UserId("member-2")))
    }

    @Test
    fun `restricted access is visible only to selected users`() {
        val access = EpisodeAccess.restricted(listOf(UserId("member-1"), UserId("member-2")))

        assertTrue(access.isVisibleTo(UserId("member-1")))
        assertTrue(access.isVisibleTo(UserId("member-2")))
        assertFalse(access.isVisibleTo(UserId("member-3")))
    }

    @Test
    fun `combined evidence uses the intersection of restricted viewers`() {
        val combined = EpisodeAccess.intersection(
            listOf(
                EpisodeAccess.PUBLIC,
                EpisodeAccess.restricted(listOf(UserId("member-1"), UserId("member-2"))),
                EpisodeAccess.restricted(listOf(UserId("member-2"), UserId("member-3"))),
            ),
        )

        assertEquals(setOf("member-2"), combined.allowedUserIds)
    }

    @Test
    fun `disjoint evidence viewers are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EpisodeAccess.intersection(
                listOf(
                    EpisodeAccess.restricted(listOf(UserId("member-1"))),
                    EpisodeAccess.restricted(listOf(UserId("member-2"))),
                ),
            )
        }
    }
}
