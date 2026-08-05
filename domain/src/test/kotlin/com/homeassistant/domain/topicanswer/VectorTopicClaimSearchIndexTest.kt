package com.homeassistant.domain.topicanswer

import com.homeassistant.core.identity.FamilyId
import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.PayloadVectorPoint
import com.homeassistant.domain.memory.PayloadVectorSearchFilter
import com.homeassistant.domain.memory.PayloadVectorSearchResult
import com.homeassistant.domain.memory.PayloadVectorStore
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorTopicClaimSearchIndexTest {
    @Test
    fun `indexes each topic claim with topic payload`() {
        val embeddingService = RecordingEmbeddingService()
        val vectorStore = RecordingPayloadVectorStore()
        val index = VectorTopicClaimSearchIndex(embeddingService, vectorStore)

        index.index(
            topic(
                id = 7,
                claims = listOf(
                    claim(1, "차단기 리모컨은 벽장 제일 위칸에 있다."),
                    claim(2, "천장등 리모컨도 함께 있다."),
                ),
            ),
        )

        assertEquals(2, vectorStore.upserted.size)
        assertEquals(
            listOf(
                "passage: 집 물건 위치\n리모컨 위치 정보\n차단기 리모컨은 벽장 제일 위칸에 있다.",
                "passage: 집 물건 위치\n리모컨 위치 정보\n천장등 리모컨도 함께 있다.",
            ),
            embeddingService.embeddedTexts,
        )
        assertEquals("topic_claim", vectorStore.upserted.first().payload["kind"])
        assertEquals("7", vectorStore.upserted.first().payload["topicId"])
        assertEquals("1", vectorStore.upserted.first().payload["claimId"])
        assertEquals(TEST_SCOPE.familyId.value, vectorStore.upserted.first().payload["familyId"])
        assertEquals(TEST_SCOPE.userId.value, vectorStore.upserted.first().payload["createdByUserId"])
        assertEquals("family-kakao.txt", vectorStore.upserted.first().payload["sourceName"])
    }

    @Test
    fun `searches topic claim vectors and maps payload to hits`() {
        val embeddingService = RecordingEmbeddingService()
        val vectorStore = RecordingPayloadVectorStore(
            searchResults = listOf(
                PayloadVectorSearchResult(
                    id = 1_000_007_001,
                    score = 0.94,
                    payload = mapOf("topicId" to "7", "claimId" to "1"),
                ),
                PayloadVectorSearchResult(
                    id = 1_000_008_001,
                    score = 0.88,
                    payload = mapOf("topicId" to "8", "claimId" to "1"),
                ),
            ),
        )
        val index = VectorTopicClaimSearchIndex(embeddingService, vectorStore)

        val hits = index.search(TEST_SCOPE, "차단기 리모컨 어디 있어?", limit = 5)

        assertEquals(listOf("query: 차단기 리모컨 어디 있어?"), embeddingService.embeddedTexts)
        assertEquals(
            PayloadVectorSearchFilter(
                must = mapOf(
                    "kind" to "topic_claim",
                    "familyId" to TEST_SCOPE.familyId.value,
                ),
            ),
            vectorStore.lastFilter,
        )
        assertEquals(5, vectorStore.lastLimit)
        assertEquals(
            listOf(
                TopicClaimSearchHit(topicId = 7, claimId = 1, score = 0.94),
                TopicClaimSearchHit(topicId = 8, claimId = 1, score = 0.88),
            ),
            hits,
        )
    }
}

private class RecordingEmbeddingService : EmbeddingService {
    val embeddedTexts = mutableListOf<String>()

    override fun embed(text: String): List<Float> {
        embeddedTexts += text
        return listOf(0.1f, 0.2f, 0.3f)
    }
}

private class RecordingPayloadVectorStore(
    private val searchResults: List<PayloadVectorSearchResult> = emptyList(),
) : PayloadVectorStore {
    val upserted = mutableListOf<PayloadVectorPoint>()
    var lastFilter: PayloadVectorSearchFilter? = null
    var lastLimit: Int? = null

    override fun upsert(point: PayloadVectorPoint) {
        upserted += point
    }

    override fun search(
        vector: List<Float>,
        filter: PayloadVectorSearchFilter,
        limit: Int,
    ): List<PayloadVectorSearchResult> {
        lastFilter = filter
        lastLimit = limit
        return searchResults
    }
}

private fun topic(id: Int, claims: List<TopicClaim>) =
    Topic(
        id = id,
        familyId = TEST_SCOPE.familyId.value,
        createdByUserId = TEST_SCOPE.userId.value,
        sourceType = "kakao",
        sourceName = "family-kakao.txt",
        title = "집 물건 위치",
        summary = "리모컨 위치 정보",
        memoryTypes = listOf(MemoryType.REFERENCE),
        domains = listOf("home"),
        evidenceRefs = claims.flatMap { it.evidenceRefs }.distinct(),
        claims = claims,
        status = CandidateStatus.APPROVED,
    )

private val TEST_SCOPE = HouseholdAccessScope(UserId("dad"), FamilyId("family-1"))

private fun claim(id: Int, text: String) =
    TopicClaim(
        id = id,
        text = text,
        subject = "리모컨",
        memoryType = MemoryType.REFERENCE,
        certainty = ClaimCertainty.SAID,
        evidenceRefs = listOf(id * 10),
    )
