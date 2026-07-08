package com.homeassistant.nlp.analysis

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.tools.Tool
import com.homeassistant.nlp.backend.openrouter.OpenRouterApiException
import com.homeassistant.nlp.backend.openrouter.OpenRouterRawResponseHolder
import com.homeassistant.nlp.topicanalysis.impl.TopicAnalysisModelEvalService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicAnalysisModelEvalServiceTest {
    @Test
    fun `runs configured models and writes raw responses with parse status`() = runBlocking {
        val outputDir = Files.createTempDirectory("topic-analysis-model-eval-test")
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/good", "model/bad"),
            outputDirectory = outputDir,
            backendFactory = { model ->
                EvalStaticBackend(
                    when (model) {
                        "model/good" -> validTopicResponse()
                        else -> invalidMemoryTypeResponse()
                    },
                )
            },
        )

        val result = service.run(
            sourceName = "sample-kakao.txt",
            text = """
                2026년 6월 15일 오전 6:43
                2026년 6월 15일 오전 6:43, 동훈 : 병원 예약 내일이지?
                2026년 6월 15일 오전 6:44, 승민 : 아기수첩 챙길게
            """.trimIndent(),
        )

        assertEquals("sample-kakao.txt", result.sourceName)
        assertEquals(listOf("model/good", "model/bad"), result.results.map { it.model })
        assertTrue(result.results.first().parseSucceeded)
        assertFalse(result.results.last().parseSucceeded)
        assertEquals(1, result.results.first().rawResponseCount)
        assertEquals(1, result.results.last().rawResponseCount)

        val report = outputDir.resolve(result.outputPath.substringAfterLast('/')).readText()
        assertContains(report, "model/good")
        assertContains(report, "parseSucceeded=true")
        assertContains(report, "model/bad")
        assertContains(report, "parseSucceeded=false")
        assertContains(report, "Failed to parse topic analysis response")
        assertContains(report, "\"memoryType\": \"PLAN\"")
        assertContains(report, "\"memoryType\": \"STATE\"")
    }

    @Test
    fun `run bundled asset can override configured models`() = runBlocking {
        val outputDir = Files.createTempDirectory("topic-analysis-model-eval-override-test")
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/default"),
            outputDirectory = outputDir,
            backendFactory = { validTopicResponse().let(::EvalStaticBackend) },
        )

        val result = service.runBundledKakaoAsset(models = listOf("model/override-a", "model/override-b"))

        assertEquals(listOf("model/override-a", "model/override-b"), result.results.map { it.model })
    }

    @Test
    fun `writes openrouter api error body as raw response`() = runBlocking {
        val outputDir = Files.createTempDirectory("topic-analysis-model-eval-api-error-test")
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/error"),
            outputDirectory = outputDir,
            backendFactory = {
                ThrowingBackend(
                    OpenRouterApiException(
                        statusCode = 400,
                        responseBody = """{"error":{"message":"response_format is not supported by this model"}}""",
                    ),
                )
            },
        )

        val result = service.run(
            sourceName = "sample-kakao.txt",
            text = """
                2026년 6월 15일 오전 6:43
                2026년 6월 15일 오전 6:43, 동훈 : 병원 예약 내일이지?
            """.trimIndent(),
        )

        assertFalse(result.results.single().parseSucceeded)
        assertEquals(1, result.results.single().rawResponseCount)
        val report = outputDir.resolve(result.outputPath.substringAfterLast('/')).readText()
        assertContains(report, "OpenRouter API error 400")
        assertContains(report, "response_format is not supported by this model")
    }

    @Test
    fun `writes openrouter token usage when backend exposes raw api response`() = runBlocking {
        val outputDir = Files.createTempDirectory("topic-analysis-model-eval-usage-test")
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/usage"),
            outputDirectory = outputDir,
            backendFactory = {
                UsageReportingBackend(
                    response = validTopicResponse(),
                    lastResponseBody = """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "{\"topics\":[]}"
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 120,
                            "completion_tokens": 34,
                            "total_tokens": 154
                          }
                        }
                    """.trimIndent(),
                )
            },
        )

        val result = service.run(
            sourceName = "sample-kakao.txt",
            text = """
                2026년 6월 15일 오전 6:43
                2026년 6월 15일 오전 6:43, 동훈 : 병원 예약 내일이지?
            """.trimIndent(),
        )

        val entry = result.results.single()
        assertEquals(120, entry.promptTokens)
        assertEquals(34, entry.completionTokens)
        assertEquals(154, entry.totalTokens)

        val report = outputDir.resolve(result.outputPath.substringAfterLast('/')).readText()
        assertContains(report, "promptTokens=120")
        assertContains(report, "completionTokens=34")
        assertContains(report, "totalTokens=154")
    }

    @Test
    fun `sends bundled source as one whole document instead of chunking`() = runBlocking {
        val backend = CapturingEvalBackend("""{"topics":[]}""")
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/whole"),
            outputDirectory = Files.createTempDirectory("topic-analysis-model-eval-whole-test"),
            backendFactory = { backend },
        )

        service.run(
            sourceName = "large-kakao.txt",
            text = kakaoExportWithMessages(201),
        )

        assertEquals(1, backend.calls)
        assertContains(backend.userMessages.single(), "r1 | 동훈 | 2026년 6월 15일 오전 6:10")
        assertContains(backend.userMessages.single(), "r201 | 동훈 | 2026년 6월 15일 오전 9:30")
    }

    @Test
    fun `runs model evaluations concurrently`() = runBlocking {
        val service = TopicAnalysisModelEvalService(
            models = listOf("model/a", "model/b", "model/c"),
            outputDirectory = Files.createTempDirectory("topic-analysis-model-eval-concurrent-test"),
            backendFactory = { DelayingBackend(200) },
        )

        val startedAt = System.currentTimeMillis()
        service.run(
            sourceName = "sample-kakao.txt",
            text = """
                2026년 6월 15일 오전 6:43
                2026년 6월 15일 오전 6:43, 동훈 : 병원 예약 내일이지?
            """.trimIndent(),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertTrue(elapsedMs < 500, "expected concurrent model calls, elapsed=${elapsedMs}ms")
    }
}

private class EvalStaticBackend(
    private val response: String,
) : LlmBackend {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse = LlmResponse.Text(response)
}

private class ThrowingBackend(
    private val error: RuntimeException,
) : LlmBackend {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse = throw error
}

private class CapturingEvalBackend(
    private val response: String,
) : LlmBackend {
    var calls = 0
    val userMessages = mutableListOf<String>()

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        calls += 1
        userMessages += messages.single().content
        return LlmResponse.Text(response)
    }
}

private class UsageReportingBackend(
    private val response: String,
    override val lastResponseBody: String,
) : LlmBackend, OpenRouterRawResponseHolder {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse = LlmResponse.Text(response)
}

private class DelayingBackend(
    private val delayMillis: Long,
) : LlmBackend {
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        delay(delayMillis)
        return LlmResponse.Text("""{"topics":[]}""")
    }
}

private fun kakaoExportWithMessages(count: Int): String =
    buildString {
        appendLine("홍승민 님과 카카오톡 대화")
        appendLine("저장한 날짜 : 2026년 6월 15일 오전 6:43")
        appendLine()
        appendLine("2026년 6월 15일 오전 6:00")
        for (index in 0 until count) {
            val totalMinutes = 6 * 60 + 10 + index
            val hour = totalMinutes / 60
            val minute = totalMinutes % 60
            appendLine("2026년 6월 15일 오전 $hour:${minute.toString().padStart(2, '0')}, 동훈 : 메시지 ${index + 1}")
        }
    }.trim()

private fun validTopicResponse(): String =
    """
    {
      "topics": [
        {
          "title": "병원 준비",
          "summary": "병원 방문 준비물을 확인했다.",
          "memoryTypes": ["CHECKLIST"],
          "domains": ["health"],
          "evidenceRecordIds": ["r1", "r2"],
          "claims": [
            {
              "text": "승민은 병원 방문 시 아기수첩을 챙기겠다고 말했다.",
              "subject": "승민",
              "memoryType": "STATE",
              "certainty": "SAID",
              "evidenceRecordIds": ["r2"]
            }
          ]
        }
      ]
    }
    """.trimIndent()

private fun invalidMemoryTypeResponse(): String =
    """
    {
      "topics": [
        {
          "title": "병원 준비",
          "summary": "병원 방문 준비물을 확인했다.",
          "memoryTypes": ["PLAN"],
          "domains": ["health"],
          "evidenceRecordIds": ["r1"],
          "claims": [
            {
              "text": "동훈은 병원 예약을 확인했다.",
              "subject": "동훈",
              "memoryType": "PLAN",
              "certainty": "SAID",
              "evidenceRecordIds": ["r1"]
            }
          ]
        }
      ]
    }
    """.trimIndent()
