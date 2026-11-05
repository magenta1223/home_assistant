package com.homeassistant.domain.component

import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentTest {
    private val ab = EpisodeAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `a component is read by whoever can read its source episode`() {
        val component = Component(1, "홍승민은 임신기간 단축근로를 한다", 10, ab)

        assertTrue(component.isVisibleTo(UserId("a")))
        assertFalse(component.isVisibleTo(UserId("c")))
    }

    @Test
    fun `a component needs a statement and a source episode`() {
        assertFailsWith<IllegalArgumentException> { Component(1, " ", 10, EpisodeAccess.PUBLIC) }
        assertFailsWith<IllegalArgumentException> { Component(1, "fact", 0, EpisodeAccess.PUBLIC) }
    }

    @Test
    fun `an access covers another when every reader of the other can read it`() {
        val a = EpisodeAccess.restricted(listOf(UserId("a")))

        assertTrue(EpisodeAccess.PUBLIC.covers(ab))
        assertTrue(ab.covers(a))
        assertFalse(a.covers(ab))
        assertFalse(ab.covers(EpisodeAccess.PUBLIC))
    }
}
