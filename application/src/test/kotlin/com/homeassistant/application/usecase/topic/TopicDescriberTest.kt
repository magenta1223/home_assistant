package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.TopicDescriptionResult
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.topic.TopicDescriptionGenerator
import com.homeassistant.application.port.output.topic.TopicDescriptionStore
import com.homeassistant.application.port.output.topic.TopicReader
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid
import com.homeassistant.domain.topic.TopicDescription
import com.homeassistant.domain.topic.TopicViewKey
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TopicDescriberTest {
    private val a = UserId("a")
    private val b = UserId("b")
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val onlyA = MemoryAccess.restricted(listOf(a))
    private val onlyB = MemoryAccess.restricted(listOf(b))

    @Test fun `another user's private memories never reach the description input`() = runBlocking<Unit> {
        val fixture = Fixture(MemoryAccess.PUBLIC to listOf(1, 2), onlyA to listOf(3), onlyB to listOf(4))

        assertEquals(TopicDescriptionResult.Described("described 1"), fixture.describer().describe(a, 1))
        assertEquals(TopicDescriptionResult.Described("described 2"), fixture.describer().describe(b, 1))

        assertEquals(listOf("memory 3", "memory 2", "memory 1"), fixture.inputs[0])
        assertEquals(listOf("memory 4", "memory 2", "memory 1"), fixture.inputs[1])
        assertEquals(
            setOf(TopicViewKey(1, setOf(MemoryAccess.PUBLIC, onlyA)), TopicViewKey(1, setOf(MemoryAccess.PUBLIC, onlyB))),
            fixture.saved.keys,
        )
    }

    @Test fun `viewers reading the same slices share one description until it grows past the threshold`() = runBlocking<Unit> {
        val fixture = Fixture(MemoryAccess.PUBLIC to (1..5).toList())
        val describer = fixture.describer()

        assertEquals(TopicDescriptionResult.Described("described 1"), describer.describe(a, 1))
        assertEquals(TopicDescriptionResult.Described("described 1"), describer.describe(b, 1))
        assertEquals(1, fixture.inputs.size)

        fixture.slices = mapOf(MemoryAccess.PUBLIC to (1..6).toList())
        assertEquals(TopicDescriptionResult.Described("described 2"), describer.describe(a, 1))
        assertEquals(TopicDescription("described 2", mapOf(MemoryAccess.PUBLIC to 6)), fixture.saved.values.single())
    }

    @Test fun `a permission change selects another view instead of the old description`() = runBlocking<Unit> {
        val fixture = Fixture(MemoryAccess.PUBLIC to listOf(1))
        fixture.describer().describe(a, 1)

        fixture.slices = mapOf(MemoryAccess.PUBLIC to listOf(1), onlyA to listOf(2))
        assertEquals(TopicDescriptionResult.Described("described 2"), fixture.describer().describe(a, 1))
        assertEquals(listOf("memory 2", "memory 1"), fixture.inputs.last())
    }

    @Test fun `a missing or unreadable topic is hidden and an unauthorized user is rejected`() = runBlocking<Unit> {
        val fixture = Fixture(onlyB to listOf(4))

        assertEquals(TopicDescriptionResult.Hidden, fixture.describer().describe(a, 1))
        assertEquals(TopicDescriptionResult.Hidden, fixture.describer().describe(a, 2))
        fixture.visible = emptySet()
        assertEquals(TopicDescriptionResult.Hidden, fixture.describer().describe(b, 1))
        assertTrue(fixture.inputs.isEmpty())
        assertFailsWith<UserAccessDeniedException> { fixture.describer(authorized = false).describe(b, 1) }
    }

    private inner class Fixture(vararg slices: Pair<MemoryAccess, List<Int>>) {
        var slices: Map<MemoryAccess, List<Int>> = slices.toMap()
        var visible: Set<Int>? = null
        val inputs = mutableListOf<List<String>>()
        val saved = mutableMapOf<TopicViewKey, TopicDescription>()

        fun describer(authorized: Boolean = true) = TopicDescriber(
            object : TopicReader {
                override fun centroids() = error("unused")
                override fun read(topicIds: Collection<Int>) = topicIds.filter { it == 1 }.map { topic() }
            },
            object : MemoryReader {
                override fun getMemories(userId: UserId) = slices.filterKeys { it.isVisibleTo(userId) }.values.flatten()
                    .filter { visible == null || it in visible!! }
                    .map { Memory(it, "creator", "memory $it", MemoryAccess.PUBLIC.visibility, emptySet(), listOf(1), it.toLong()) }
            },
            object : TopicDescriptionStore {
                override fun find(key: TopicViewKey) = saved[key]
                override fun save(key: TopicViewKey, description: TopicDescription) { saved[key] = description }
            },
            TopicDescriptionGenerator { inputs.add(it); "  described ${inputs.size}  " },
            UserAccessPolicy { authorized },
        )

        private fun topic(): Topic {
            val count = slices.values.sumOf { it.size }
            return Topic(1, (2..count).fold(TopicCentroid.of(east)) { sum, _ -> sum + east }, slices)
        }
    }
}
