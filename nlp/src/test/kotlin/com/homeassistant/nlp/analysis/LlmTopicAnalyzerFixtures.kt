package com.homeassistant.nlp.analysis

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.core.tools.Tool
import com.homeassistant.nlp.topicanalysis.impl.LlmTopicAnalyzer

internal fun serviceFor(response: String): LlmTopicAnalyzer =
    LlmTopicAnalyzer(StaticBackend(response))

internal fun singleRecordDocument(): SourceDocument =
    SourceDocument(
        sourceType = "kakao",
        sourceName = "2026-06-07.txt",
        records = listOf(SourceRecord("r1", 1, "동훈 | 오후 4:49 | 따랑해")),
    )

internal fun documentWithRecords(count: Int): SourceDocument =
    SourceDocument(
        sourceType = "kakao",
        sourceName = "2026-06-07.txt",
        records = (1..count).map { index ->
            SourceRecord("r$index", index, "동훈 | 오후 4:49 | 기록 $index")
        },
    )

internal fun topicJson(
    title: String = "관계 표현",
    summary: String = "애정 표현을 주고받았다.",
    memoryTypes: String = """["STATE"]""",
    domains: String = """["relationship"]""",
    evidenceRecordIds: String = """["r1"]""",
    claimText: String = "동훈은 애정 표현을 했다.",
    claimSubject: String = "동훈",
    claimMemoryType: String = "STATE",
    claimCertainty: String = "OBSERVED",
    claimEvidenceRecordIds: String = """["r1"]""",
    claims: String = """
        [
          {
            "text": "$claimText",
            "subject": "$claimSubject",
            "memoryType": "$claimMemoryType",
            "certainty": "$claimCertainty",
            "evidenceRecordIds": $claimEvidenceRecordIds
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
          "domains": $domains,
          "evidenceRecordIds": $evidenceRecordIds,
          "claims": $claims
        }
      ]
    }
""".trimIndent()

internal class StaticBackend(private val response: String) : LlmBackend {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse = LlmResponse.Text(response)
}

internal class CapturingBackend(private val response: String) : LlmBackend {
    var system = ""
    lateinit var messages: List<Message>
    var outputSchema = ""

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        this.system = system
        this.messages = messages
        this.outputSchema = outputSchema
        return LlmResponse.Text(response)
    }
}

internal data class BackendCall(
    val system: String,
    val messageContent: String,
    val outputSchema: String,
)

internal class RecordingBackend(responses: List<String>) : LlmBackend {
    private val responses = ArrayDeque(responses)
    val calls = mutableListOf<BackendCall>()

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        calls += BackendCall(system, messages.single().content, outputSchema)
        return LlmResponse.Text(responses.removeFirst())
    }
}
