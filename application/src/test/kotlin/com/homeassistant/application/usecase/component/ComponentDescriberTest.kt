package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.ComponentDescriptionResult
import com.homeassistant.application.port.output.episode.read.EpisodeReader
import com.homeassistant.application.port.output.component.ComponentDescriptionGenerator
import com.homeassistant.application.port.output.component.ComponentDescriptionStore
import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.component.ComponentCentroid
import com.homeassistant.domain.component.ComponentDescription
import com.homeassistant.domain.component.ComponentViewKey
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ComponentDescriberTest {
    private val a = UserId("a")
    private val b = UserId("b")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val onlyA = EpisodeAccess.restricted(listOf(a))
    private val onlyB = EpisodeAccess.restricted(listOf(b))

    @Test fun `another user's private episodes never reach the description input`() = runBlocking<Unit> {
        val fixture = Fixture(EpisodeAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3), onlyB to listOf(4))

        assertEquals(ComponentDescriptionResult.Described("described 1"), fixture.describer().describe(a, 1))
        assertEquals(ComponentDescriptionResult.Described("described 2"), fixture.describer().describe(b, 1))

        assertEquals(listOf("episode 3", "episode 2", "episode 1"), fixture.inputs[0])
        assertEquals(listOf("episode 4", "episode 2", "episode 1"), fixture.inputs[1])
        assertEquals(
            setOf(ComponentViewKey(1, setOf(EpisodeAccess.PUBLIC, onlyA)), ComponentViewKey(1, setOf(EpisodeAccess.PUBLIC, onlyB))),
            fixture.saved.keys,
        )
    }

    @Test fun `viewers reading the same slices share one description until it grows past the threshold`() = runBlocking<Unit> {
        val fixture = Fixture(EpisodeAccess.PUBLIC to (1..5).toList())
        val describer = fixture.describer()

        assertEquals(ComponentDescriptionResult.Described("described 1"), describer.describe(a, 1))
        assertEquals(ComponentDescriptionResult.Described("described 1"), describer.describe(b, 1))
        assertEquals(1, fixture.inputs.size)

        fixture.slices = mapOf(EpisodeAccess.PUBLIC to (1..6).toList())
        assertEquals(ComponentDescriptionResult.Described("described 2"), describer.describe(a, 1))
        assertEquals(ComponentDescription("described 2", mapOf(EpisodeAccess.PUBLIC to 6)), fixture.saved.values.single())
    }

    @Test fun `a permission change selects another view instead of the old description`() = runBlocking<Unit> {
        val fixture = Fixture(EpisodeAccess.PUBLIC to listOf(1))
        fixture.describer().describe(a, 1)

        fixture.slices = mapOf(EpisodeAccess.PUBLIC to listOf(1), onlyA to listOf(2))
        assertEquals(ComponentDescriptionResult.Described("described 2"), fixture.describer().describe(a, 1))
        assertEquals(listOf("episode 2", "episode 1"), fixture.inputs.last())
    }

    @Test fun `a missing or unreadable component is hidden and an unauthorized user is rejected`() = runBlocking<Unit> {
        val fixture = Fixture(onlyB to listOf(4))

        assertEquals(ComponentDescriptionResult.Hidden, fixture.describer().describe(a, 1))
        assertEquals(ComponentDescriptionResult.Hidden, fixture.describer().describe(a, 2))
        fixture.visible = emptySet()
        assertEquals(ComponentDescriptionResult.Hidden, fixture.describer().describe(b, 1))
        assertTrue(fixture.inputs.isEmpty())
        assertFailsWith<UserAccessDeniedException> { fixture.describer(authorized = false).describe(b, 1) }
    }

    private inner class Fixture(vararg slices: Pair<EpisodeAccess, List<Int>>) {
        var slices: Map<EpisodeAccess, List<Int>> = slices.toMap()
        var visible: Set<Int>? = null
        val inputs = mutableListOf<List<String>>()
        val saved = mutableMapOf<ComponentViewKey, ComponentDescription>()

        fun describer(authorized: Boolean = true) = ComponentDescriber(
            object : ComponentReader {
                override fun centroids() = error("unused")
                override fun read(componentIds: Collection<Int>) = componentIds.filter { it == 1 }.map { component() }
            },
            object : EpisodeReader {
                override fun getEpisodes(userId: UserId) = slices.filterKeys { it.isVisibleTo(userId) }.values.flatten()
                    .filter { visible == null || it in visible!! }
                    .map { Episode(it, "creator", "title $it", "episode $it", EpisodeAccess.PUBLIC.visibility, emptySet(), listOf(1), it.toLong()) }
            },
            object : ComponentDescriptionStore {
                override fun find(key: ComponentViewKey) = saved[key]
                override fun save(key: ComponentViewKey, description: ComponentDescription) { saved[key] = description }
            },
            ComponentDescriptionGenerator { inputs.add(it); "  described ${inputs.size}  " },
            UserAccessPolicy { authorized },
        )

        private fun component(): Component {
            val count = slices.values.sumOf { it.size }
            return Component(1, (2..count).fold(ComponentCentroid.of(east)) { sum, _ -> sum + east }, slices)
        }
    }
}
