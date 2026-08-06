package com.homeassistant.application.memory.answer

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AnswerFromMemoryTest {
    @Test
    fun `answers from vector topic claim hits`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(
                listOf(
                    topic(
                        id = 7,
                        title = "집 물건 위치",
                        claimTexts = listOf(
                            "주차장 차단기 리모컨은 벽장 제일 위칸에 있다.",
                            "동훈은 집안일 체크리스트를 나열했다.",
                        ),
                    ),
                )
            ),
            memorySearchIndex = FakeMemorySearchIndex(
                listOf(MemorySearchHit(topicId = 7, memoryId = 1, score = 0.91)),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("차단기 리모컨 어디 있어?", 5))

        assertTrue(result.answer.contains("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."))
        assertTrue(!result.answer.contains("집안일 체크리스트"))
        assertEquals(1, result.matches.size)
        assertEquals(7, result.matches.single().topicId)
        assertEquals("주차장 차단기 리모컨은 벽장 제일 위칸에 있다.", result.matches.single().content)
    }

    @Test
    fun `returns no match answer when approved topics do not match`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(emptyList()),
            memorySearchIndex = FakeMemorySearchIndex(emptyList()),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("없는 질문", 5))

        assertEquals("승인된 기억에서 관련 내용을 찾지 못했습니다.", result.answer)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `answer text uses strongest match only`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(
                listOf(
                    topic(1, "리모컨 위치", "리모컨은 벽장 제일 위칸에 있다."),
                    topic(2, "보안 리모컨", "보안 리모컨은 잘 해제하고 나가는 습관을 들이자고 했다."),
                )
            ),
            memorySearchIndex = FakeMemorySearchIndex(
                listOf(
                    MemorySearchHit(topicId = 1, memoryId = 1, score = 0.92),
                    MemorySearchHit(topicId = 2, memoryId = 1, score = 0.74),
                ),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("리모컨 어디", 5))

        assertEquals("저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.", result.answer)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun `clamps requested limit`() {
        val topics = List(12) { topic(it + 1, "후보 $it", "리모컨 claim $it") }
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(topics),
            memorySearchIndex = FakeMemorySearchIndex(
                topics.map { MemorySearchHit(topicId = it.id, memoryId = 1, score = 1.0) },
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("리모컨", 50))

        assertEquals(10, result.matches.size)
    }

    @Test
    fun `preserves vector hit ordering when hydrating topics`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(
                listOf(
                    topic(1, "첫번째", "첫번째 claim"),
                    topic(2, "두번째", "두번째 claim"),
                ),
            ),
            memorySearchIndex = FakeMemorySearchIndex(
                listOf(
                    MemorySearchHit(topicId = 2, memoryId = 1, score = 0.93),
                    MemorySearchHit(topicId = 1, memoryId = 1, score = 0.91),
                ),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("순서", 5))

        assertEquals(listOf(2, 1), result.matches.map { it.topicId })
        assertEquals("저장된 기억 기준으로는 두번째 claim", result.answer)
    }

    @Test
    fun `rejects an unauthorized user and family pair before vector search`() {
        val index = FakeMemorySearchIndex(emptyList())
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(emptyList()),
            memorySearchIndex = index,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        assertFailsWith<HouseholdAccessDeniedException> {
            service.answer(
                MemoryAnswerRequest(
                    userId = "attacker",
                    question = "비밀",
                ),
            )
        }
        assertEquals(null, index.lastUserId)
    }

    @Test
    fun `keeps a globally visible household hit during sql hydration`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(
                listOf(topic(7, "가족 공용", "전역에서 보여야 함")),
            ),
            memorySearchIndex = FakeMemorySearchIndex(
                listOf(MemorySearchHit(7, 1, 1.0)),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("비밀", 5))

        assertEquals(1, result.matches.size)
    }

    @Test
    fun `does not substitute another memory when a vector hit is stale or invisible`() {
        val service = AnswerFromMemory(
            topicStore = FakeTopicStore(
                listOf(topic(7, "가족 공용", "보이는 다른 기억")),
            ),
            memorySearchIndex = FakeMemorySearchIndex(
                listOf(MemorySearchHit(topicId = 7, memoryId = 999, score = 1.0)),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("비공개 기억", 5))

        assertEquals(emptyList(), result.matches)
        assertEquals("승인된 기억에서 관련 내용을 찾지 못했습니다.", result.answer)
    }
}

private class FakeTopicStore(private val topics: List<Topic>) : TopicAnalysisStore {
    override fun createTopic(candidate: TopicCandidate): Topic =
        error("not used")

    override fun searchApprovedTopics(
        userId: UserId,
        query: String,
        limit: Int,
    ): List<Topic> =
        topics.take(limit.coerceIn(1, 10))

    override fun getApprovedTopics(
        userId: UserId,
        topicIds: Collection<Int>,
    ): List<Topic> =
        topics.filter { it.id in topicIds.toSet() }

    override fun getTopicsForMemoryIndexing(memoryIds: Collection<Int>): List<Topic> =
        topics.mapNotNull { topic ->
            topic.memories.filter { it.id in memoryIds }.takeIf { it.isNotEmpty() }
                ?.let { topic.copy(memories = it) }
        }
}

private class FakeMemorySearchIndex(
    private val hits: List<MemorySearchHit>,
) : MemorySearchIndex {
    var lastUserId: UserId? = null

    override fun index(document: MemorySearchDocument) = Unit

    override fun search(
        userId: UserId,
        question: String,
        limit: Int,
    ): List<MemorySearchHit> {
        lastUserId = userId
        return hits.take(limit.coerceIn(1, 10))
    }
}

private fun topic(
    id: Int,
    title: String,
    claimText: String,
) = topic(id, title, listOf(claimText))

private fun topic(
    id: Int,
    title: String,
    claimTexts: List<String>,
) =
    Topic(
        id = id,
        createdByUserId = TEST_USER.value,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = title,
        summary = "$title 요약",
        categories = listOf("home"),
        memories = claimTexts.mapIndexed { index, claimText ->
            TopicClaim(
                id = index + 1,
                text = claimText,
                subject = title,
                memoryType = MemoryType.REFERENCE,
                certainty = ClaimCertainty.SAID,
                evidenceRefs = listOf(id * 10),
            )
        },
        status = CandidateStatus.APPROVED,
    )

private fun request(question: String, limit: Int): MemoryAnswerRequest =
    MemoryAnswerRequest(
        userId = TEST_USER.value,
        question = question,
        limit = limit,
    )

private val TEST_USER = UserId("dad")
private val TEST_ACCESS_POLICY = HouseholdAccessPolicy { it == TEST_USER }
