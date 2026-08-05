package com.homeassistant.application.topicanswer.answer

import com.homeassistant.core.identity.FamilyId
import com.homeassistant.core.identity.HouseholdAccessDeniedException
import com.homeassistant.core.identity.HouseholdAccessPolicy
import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TopicAnswerServiceTest {
    @Test
    fun `answers from vector topic claim hits`() {
        val service = TopicAnswerService(
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
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(
                listOf(TopicClaimSearchHit(topicId = 7, claimId = 1, score = 0.91)),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("차단기 리모컨 어디 있어?", 5))

        assertTrue(result.answer.contains("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."))
        assertTrue(!result.answer.contains("집안일 체크리스트"))
        assertEquals(1, result.matches.size)
        assertEquals(7, result.matches.single().topicId)
        assertEquals(listOf("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."), result.matches.single().claims)
    }

    @Test
    fun `returns no match answer when approved topics do not match`() {
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(emptyList()),
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(emptyList()),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("없는 질문", 5))

        assertEquals("승인된 기억에서 관련 내용을 찾지 못했습니다.", result.answer)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `answer text uses strongest match only`() {
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(
                listOf(
                    topic(1, "리모컨 위치", "리모컨은 벽장 제일 위칸에 있다."),
                    topic(2, "보안 리모컨", "보안 리모컨은 잘 해제하고 나가는 습관을 들이자고 했다."),
                )
            ),
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(
                listOf(
                    TopicClaimSearchHit(topicId = 1, claimId = 1, score = 0.92),
                    TopicClaimSearchHit(topicId = 2, claimId = 1, score = 0.74),
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
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(topics),
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(
                topics.map { TopicClaimSearchHit(topicId = it.id, claimId = 1, score = 1.0) },
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("리모컨", 50))

        assertEquals(10, result.matches.size)
    }

    @Test
    fun `preserves vector hit ordering when hydrating topics`() {
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(
                listOf(
                    topic(1, "첫번째", "첫번째 claim"),
                    topic(2, "두번째", "두번째 claim"),
                ),
            ),
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(
                listOf(
                    TopicClaimSearchHit(topicId = 2, claimId = 1, score = 0.93),
                    TopicClaimSearchHit(topicId = 1, claimId = 1, score = 0.91),
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
        val index = FakeTopicClaimSearchIndex(emptyList())
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(emptyList()),
            topicClaimSearchIndex = index,
            accessPolicy = TEST_ACCESS_POLICY,
        )

        assertFailsWith<HouseholdAccessDeniedException> {
            service.answer(
                TopicAnswerRequest(
                    userId = "attacker",
                    familyId = TEST_SCOPE.familyId.value,
                    question = "비밀",
                ),
            )
        }
        assertEquals(null, index.lastScope)
    }

    @Test
    fun `drops a cross-family vector hit during sql hydration`() {
        val service = TopicAnswerService(
            topicStore = FakeTopicStore(
                listOf(topic(7, "다른 가족", "노출되면 안 됨", familyId = "other-family")),
            ),
            topicClaimSearchIndex = FakeTopicClaimSearchIndex(
                listOf(TopicClaimSearchHit(7, 1, 1.0)),
            ),
            accessPolicy = TEST_ACCESS_POLICY,
        )

        val result = service.answer(request("비밀", 5))

        assertEquals(emptyList(), result.matches)
    }
}

private class FakeTopicStore(private val topics: List<Topic>) : TopicAnalysisStore {
    override fun createTopic(candidate: TopicCandidate): Topic =
        error("not used")

    override fun searchApprovedTopics(
        scope: HouseholdAccessScope,
        query: String,
        limit: Int,
    ): List<Topic> =
        topics.filter { it.familyId == scope.familyId.value }.take(limit.coerceIn(1, 10))

    override fun getApprovedTopics(
        scope: HouseholdAccessScope,
        topicIds: Collection<Int>,
    ): List<Topic> =
        topics.filter { it.familyId == scope.familyId.value && it.id in topicIds.toSet() }

    override fun getApprovedTopicsForIndexing(topicIds: Collection<Int>): List<Topic> =
        topics.filter { it.id in topicIds.toSet() }
}

private class FakeTopicClaimSearchIndex(
    private val hits: List<TopicClaimSearchHit>,
) : TopicClaimSearchIndex {
    var lastScope: HouseholdAccessScope? = null

    override fun index(topic: Topic) = Unit

    override fun search(
        scope: HouseholdAccessScope,
        question: String,
        limit: Int,
    ): List<TopicClaimSearchHit> {
        lastScope = scope
        return hits.take(limit.coerceIn(1, 10))
    }
}

private fun topic(
    id: Int,
    title: String,
    claimText: String,
    familyId: String = TEST_SCOPE.familyId.value,
) = topic(id, title, listOf(claimText), familyId)

private fun topic(
    id: Int,
    title: String,
    claimTexts: List<String>,
    familyId: String = TEST_SCOPE.familyId.value,
) =
    Topic(
        id = id,
        familyId = familyId,
        createdByUserId = TEST_SCOPE.userId.value,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = title,
        summary = "$title 요약",
        memoryTypes = listOf(MemoryType.REFERENCE),
        domains = listOf("home"),
        evidenceRefs = listOf(id * 10),
        claims = claimTexts.mapIndexed { index, claimText ->
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

private fun request(question: String, limit: Int): TopicAnswerRequest =
    TopicAnswerRequest(
        userId = TEST_SCOPE.userId.value,
        familyId = TEST_SCOPE.familyId.value,
        question = question,
        limit = limit,
    )

private val TEST_SCOPE = HouseholdAccessScope(UserId("dad"), FamilyId("family-1"))
private val TEST_ACCESS_POLICY = HouseholdAccessPolicy { it == TEST_SCOPE }
