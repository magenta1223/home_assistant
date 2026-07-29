package com.homeassistant.domain.topicanswer

import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.domain.memory.EmbeddingService
import com.homeassistant.domain.memory.PayloadVectorPoint
import com.homeassistant.domain.memory.PayloadVectorSearchFilter
import com.homeassistant.domain.memory.PayloadVectorStore

internal class VectorTopicClaimSearchIndex(
    private val embeddingService: EmbeddingService,
    private val vectorStore: PayloadVectorStore,
) : TopicClaimSearchIndex {
    override fun index(topic: Topic) {
        topic.claims.forEach { claim ->
            vectorStore.upsert(
                PayloadVectorPoint(
                    id = pointId(topic.id, claim.id),
                    vector = embeddingService.embed("passage: ${topic.title}\n${topic.summary}\n${claim.text}"),
                    payload = mapOf(
                        "kind" to TOPIC_CLAIM_KIND,
                        "familyId" to topic.familyId,
                        "createdByUserId" to topic.createdByUserId,
                        "topicId" to topic.id.toString(),
                        "claimId" to claim.id.toString(),
                        "sourceType" to topic.sourceType,
                        "sourceName" to topic.sourceName,
                        "memoryType" to claim.memoryType.code,
                        "subject" to claim.subject,
                    ),
                ),
            )
        }
    }

    override fun search(
        scope: HouseholdAccessScope,
        question: String,
        limit: Int,
    ): List<TopicClaimSearchHit> =
        vectorStore
            .search(
                vector = embeddingService.embed("query: $question"),
                filter = PayloadVectorSearchFilter(
                    must = mapOf(
                        "kind" to TOPIC_CLAIM_KIND,
                        "familyId" to scope.familyId.value,
                    ),
                ),
                limit = limit.coerceIn(1, 10),
            )
            .mapNotNull { result ->
                val topicId = result.payload["topicId"]?.toIntOrNull()
                val claimId = result.payload["claimId"]?.toIntOrNull()
                if (topicId == null || claimId == null) null
                else TopicClaimSearchHit(topicId = topicId, claimId = claimId, score = result.score)
            }

    private fun pointId(topicId: Int, claimId: Int): Int {
        val rawId = topicId.toLong() * TOPIC_ID_MULTIPLIER + claimId
        require(rawId in 0 until POINT_ID_NAMESPACE) {
            "Topic claim vector point id is out of range: topicId=$topicId claimId=$claimId"
        }
        return POINT_ID_NAMESPACE + rawId.toInt()
    }

    private companion object {
        const val TOPIC_CLAIM_KIND = "topic_claim"
        const val POINT_ID_NAMESPACE = 1_000_000_000
        const val TOPIC_ID_MULTIPLIER = 1_000
    }
}

object TopicClaimSearchIndexFactory {
    fun create(
        embeddingService: EmbeddingService,
        vectorStore: PayloadVectorStore,
    ): TopicClaimSearchIndex =
        VectorTopicClaimSearchIndex(embeddingService, vectorStore)
}
