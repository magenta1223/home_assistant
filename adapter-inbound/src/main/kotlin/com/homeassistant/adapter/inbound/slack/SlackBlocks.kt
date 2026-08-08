package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryVisibility

object SlackMemoryBlocks {
    fun analysisMessage(
        sourceName: String,
        importedRecordCount: Int,
        memories: List<MemoryProposal>,
    ): Map<String, Any> =
        mapOf(
            "text" to "Kakao 대화 분석 및 저장 완료: ${memories.size}개",
            "blocks" to listOf(
                section(
                    "*Kakao 대화 분석 및 저장 완료: ${memories.size}개*\n" +
                        "${plain(sourceName, 140)}\n" +
                        "파싱 메시지 ${importedRecordCount}건 | " +
                        "공개 ${memories.count { it.visibility == MemoryVisibility.PUBLIC }}개 | " +
                        "비공개 ${memories.count { it.visibility == MemoryVisibility.PRIVATE }}개",
                ),
                divider(),
            ) + memories.take(5).mapIndexed { index, memory -> memorySummary(index, memory) },
        )

    private fun memorySummary(index: Int, memory: MemoryProposal): Map<String, Any> =
        section(
            "*${index + 1}. ${mrkdwn(memory.subject, 140)}*\n" +
                "${mrkdwn(memory.content, 700)}\n" +
                "유형: ${memory.memoryType} | 근거: ${memory.evidenceIds.size}개",
        )

    private fun section(text: String): Map<String, Any> =
        mapOf(
            "type" to "section",
            "text" to mapOf("type" to "mrkdwn", "text" to text),
        )

    private fun divider(): Map<String, Any> = mapOf("type" to "divider")

    private fun mrkdwn(text: String, maxLength: Int): String = plain(text, maxLength)

    private fun plain(text: String, maxLength: Int): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxLength) return compact
        return compact.take(maxLength - 1).trimEnd() + "…"
    }
}
