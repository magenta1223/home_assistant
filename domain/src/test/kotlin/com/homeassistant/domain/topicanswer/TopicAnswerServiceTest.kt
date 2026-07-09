package com.homeassistant.domain.topicanswer

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicAnswerServiceTest {
    @Test
    fun `answers from approved topic claims`() {
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
            )
        )

        val result = service.answer(TopicAnswerRequest(question = "차단기 리모컨 어디 있어?", limit = 5))

        assertTrue(result.answer.contains("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."))
        assertTrue(!result.answer.contains("집안일 체크리스트"))
        assertEquals(1, result.matches.size)
        assertEquals(7, result.matches.single().topicId)
        assertEquals(listOf("주차장 차단기 리모컨은 벽장 제일 위칸에 있다."), result.matches.single().claims)
    }

    @Test
    fun `returns no match answer when approved topics do not match`() {
        val service = TopicAnswerService(topicStore = FakeTopicStore(emptyList()))

        val result = service.answer(TopicAnswerRequest(question = "없는 질문", limit = 5))

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
            )
        )

        val result = service.answer(TopicAnswerRequest(question = "리모컨 어디", limit = 5))

        assertEquals("저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.", result.answer)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun `clamps requested limit`() {
        val service = TopicAnswerService(topicStore = FakeTopicStore(List(12) { topic(it + 1, "후보 $it", "리모컨 claim $it") }))

        val result = service.answer(TopicAnswerRequest(question = "리모컨", limit = 50))

        assertEquals(10, result.matches.size)
    }
}

private class FakeTopicStore(private val topics: List<Topic>) : TopicAnalysisStore {
    override fun createTopic(candidate: TopicCandidate): Topic =
        error("not used")

    override fun searchApprovedTopics(query: String, limit: Int): List<Topic> =
        topics.take(limit.coerceIn(1, 10))
}

private fun topic(id: Int, title: String, claimText: String) =
    topic(id, title, listOf(claimText))

private fun topic(id: Int, title: String, claimTexts: List<String>) =
    Topic(
        id = id,
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
