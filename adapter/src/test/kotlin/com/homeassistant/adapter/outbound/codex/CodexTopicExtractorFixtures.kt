package com.homeassistant.adapter.outbound.codex

import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord

internal fun serviceFor(response: String): CodexTopicExtractor =
    CodexTopicExtractor(StaticClient(response))

internal fun singleRecordDocument(): SourceDocument =
    SourceDocument(
        source = SourceDescriptor("kakao", "2026-06-07.txt"),
        records = listOf(sourceRecord(1, "동훈 | 오후 4:49 | 따랑해")),
    )

internal fun documentWithRecords(count: Int): SourceDocument =
    SourceDocument(
        source = SourceDescriptor("kakao", "2026-06-07.txt"),
        records = (1..count).map { index ->
            sourceRecord(index, "동훈 | 오후 4:49 | 기록 $index")
        },
    )

internal fun sourceRecord(id: Int, content: String): SourceRecord =
    SourceRecord(
        id = id,
        deduplicationKey = "key-$id",
        content = content,
    )

internal fun topicJson(
    title: String = "관계 표현",
    summary: String = "애정 표현을 주고받았다.",
    memoryTypes: String = """["STATE"]""",
    categories: String = """["relationship"]""",
    evidenceRecordIds: String = """["r1"]""",
    memoryText: String = "동훈은 애정 표현을 했다.",
    memorySubject: String = "동훈",
    memoryType: String = "STATE",
    memoryCertainty: String = "OBSERVED",
    memoryEvidenceRecordIds: String = """["r1"]""",
    memories: String = """
        [
          {
            "text": "$memoryText",
            "subject": "$memorySubject",
            "memoryType": "$memoryType",
            "certainty": "$memoryCertainty",
            "evidenceRecordIds": $memoryEvidenceRecordIds
          }
        ]
    """.trimIndent(),
): String = """
    {
      "topics": [
        {
          "title": "$title",
          "summary": "$summary",
          "memoryTypes": $memoryTypes,
          "categories": $categories,
          "evidenceRecordIds": $evidenceRecordIds,
          "memories": $memories
        }
      ]
    }
""".trimIndent()

internal class StaticClient(private val response: String) : CodexCompletionClient {
    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
    ): String = response
}

internal class CapturingClient(private val response: String) : CodexCompletionClient {
    var system = ""
    var userMessage = ""
    var outputSchema = ""

    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
    ): String {
        this.system = system
        this.userMessage = userMessage
        this.outputSchema = outputSchema
        return response
    }
}

internal data class BackendCall(
    val system: String,
    val messageContent: String,
    val outputSchema: String,
)

internal class RecordingClient(responses: List<String>) : CodexCompletionClient {
    private val responses = ArrayDeque(responses)
    val calls = mutableListOf<BackendCall>()

    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
    ): String {
        calls += BackendCall(system, userMessage, outputSchema)
        return responses.removeFirst()
    }
}
