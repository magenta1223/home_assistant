package com.homeassistant.app.slack

import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SlackTopicBlocksTest {
    @Test
    fun `analysis message renders preview topics and approval button`() {
        val message = SlackTopicBlocks.analysisMessage(
            previewId = "preview-1",
            sourceName = "family-kakao.txt",
            importedRecordCount = 2,
            topics = listOf(topic("이사 준비", "관리사무소 질문을 모았다.")),
        )

        val blocks = message.blocks()
        assertEquals("section", blocks[0]["type"])
        assertTrue(blocks.textAt(0).contains("Kakao 대화 분석 후보 1개"))
        assertTrue(blocks.textAt(2).contains("이사 준비"))
        assertTrue(blocks.textAt(2).contains("STATE"))

        val reviewButton = blocks.last().elements().single()
        assertEquals("button", reviewButton["type"])
        assertEquals(SlackTopicBlocks.ACTION_OPEN_REVIEW, reviewButton["action_id"])
        assertEquals("preview-1", reviewButton["value"])
        assertEquals("승인", reviewButton.textObject())
    }

    @Test
    fun `analysis message truncates long title and summary safely`() {
        val message = SlackTopicBlocks.analysisMessage(
            previewId = "preview-1",
            sourceName = "family-kakao.txt",
            importedRecordCount = 2,
            topics = listOf(topic("가".repeat(300), "나".repeat(2000))),
        )

        val text = message.blocks().textAt(2)
        assertTrue(text.length < 950)
        assertTrue(text.contains("…"))
    }

    @Test
    fun `selection modal stores preview id as private metadata and selects all topics initially`() {
        val result = SlackTopicBlocks.selectionModal(
            previewId = "preview-1",
            topics = listOf(
                topic("첫 후보", "요약"),
                topic("둘째 후보", "요약"),
            ),
        )

        val modal = assertIs<SlackModalBuildResult.Modal>(result).view
        assertEquals("preview-1", modal["private_metadata"])
        assertEquals(SlackTopicBlocks.CALLBACK_CONFIRM_TOPICS, modal["callback_id"])
        assertEquals("기억 후보 승인", modal.textObject("title"))
        assertEquals("승인", modal.textObject("submit"))
        assertTrue(modal.blocks().textAt(0).contains("승인할 후보를 선택하세요."))

        val select = modal.blocks()[1].element()
        val options = select.options()
        assertEquals(listOf("0", "1"), options.map { it["value"] })
        assertEquals(options, select["initial_options"])
    }

    @Test
    fun `selection modal rejects more than 100 topics`() {
        val result = SlackTopicBlocks.selectionModal(
            previewId = "preview-1",
            topics = List(101) { topic("후보 $it", "요약") },
        )

        val tooMany = assertIs<SlackModalBuildResult.TooManyTopics>(result)
        assertEquals(101, tooMany.actualCount)
        assertEquals(100, tooMany.maxCount)
    }

    @Test
    fun `selection option label and description do not include evidence or claim text`() {
        val result = SlackTopicBlocks.selectionModal(
            previewId = "preview-1",
            topics = listOf(topic("첫 후보", "비밀 원문 메시지")),
        )

        val modal = assertIs<SlackModalBuildResult.Modal>(result).view
        val option = modal.blocks()[1].element().options().single()
        val optionText = option.textObject()
        val descriptionText = option.descriptionObject()

        assertTrue(optionText.contains("첫 후보"))
        assertTrue(!optionText.contains("비밀 원문 메시지"))
        assertTrue(!descriptionText.contains("비밀 원문 메시지"))
        assertTrue(!descriptionText.contains("동훈은 비밀 원문을 말했다."))
    }

    private fun topic(title: String, summary: String) =
        TopicCandidate(
            sourceType = "kakao",
            sourceName = "family-kakao.txt",
            title = title,
            summary = summary,
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("family"),
            evidenceRefs = listOf(1, 2),
            claims = listOf(
                TopicClaimCandidate(
                    text = "동훈은 비밀 원문을 말했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidenceRefs = listOf(1),
                ),
            ),
        )
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.blocks(): List<Map<String, Any>> =
    this["blocks"] as List<Map<String, Any>>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.elements(): List<Map<String, Any>> =
    this["elements"] as List<Map<String, Any>>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.element(): Map<String, Any> =
    this["element"] as Map<String, Any>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.options(): List<Map<String, Any>> =
    this["options"] as List<Map<String, Any>>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.textObject(): String =
    (this["text"] as Map<String, Any>)["text"] as String

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.textObject(key: String): String =
    (this[key] as Map<String, Any>)["text"] as String

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.descriptionObject(): String =
    (this["description"] as Map<String, Any>)["text"] as String

@Suppress("UNCHECKED_CAST")
private fun List<Map<String, Any>>.textAt(index: Int): String =
    (this[index]["text"] as Map<String, Any>)["text"] as String
