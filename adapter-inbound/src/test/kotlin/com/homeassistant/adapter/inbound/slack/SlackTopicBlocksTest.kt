package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackTopicBlocksTest {
    @Test
    fun `analysis message renders saved topics without approval controls`() {
        val message = SlackTopicBlocks.analysisMessage(
            sourceName = "family-kakao.txt",
            importedRecordCount = 2,
            topics = listOf(topic("이사 준비", "관리사무소 질문을 모았다.")),
        )

        val blocks = message.blocks()
        assertEquals("section", blocks[0]["type"])
        assertTrue(blocks.textAt(0).contains("Kakao 대화 분석 및 저장 완료: 1개"))
        assertTrue(blocks.textAt(2).contains("이사 준비"))
        assertTrue(blocks.textAt(2).contains("STATE"))
        assertEquals(3, blocks.size)
    }

    @Test
    fun `analysis message truncates long title and summary safely`() {
        val message = SlackTopicBlocks.analysisMessage(
            sourceName = "family-kakao.txt",
            importedRecordCount = 2,
            topics = listOf(topic("가".repeat(300), "나".repeat(2000))),
        )

        val text = message.blocks().textAt(2)
        assertTrue(text.length < 950)
        assertTrue(text.contains("…"))
    }

    private fun topic(title: String, summary: String) =
        TopicProposal(
            title = title,
            summary = summary,
            categories = listOf("family"),
            memories = listOf(
                MemoryProposal(
                    content = "동훈은 비밀 원문을 말했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = MemoryCertainty.OBSERVED,
                    evidenceIds = listOf(1),
                ),
            ),
        )
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.blocks(): List<Map<String, Any>> =
    this["blocks"] as List<Map<String, Any>>

@Suppress("UNCHECKED_CAST")
private fun List<Map<String, Any>>.textAt(index: Int): String =
    (this[index]["text"] as Map<String, Any>)["text"] as String
