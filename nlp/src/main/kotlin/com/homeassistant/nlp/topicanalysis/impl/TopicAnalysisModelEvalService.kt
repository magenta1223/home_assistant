package com.homeassistant.nlp.topicanalysis.impl

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.core.tools.Tool
import com.homeassistant.domain.kakao.KakaoMessageParser
import com.homeassistant.nlp.backend.openrouter.OpenRouterApiException
import com.homeassistant.nlp.backend.openrouter.OpenRouterRawResponseHolder
import com.homeassistant.nlp.backend.openrouter.OpenRouterResponseParser
import com.homeassistant.nlp.backend.openrouter.OpenRouterUsage
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisModelEvalResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisModelEvalRunResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisModelEvalUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TopicAnalysisModelEvalService(
    private val models: List<String> = DEFAULT_MODELS,
    private val outputDirectory: Path = Path.of("build", "topic-analysis-model-evals"),
    private val backendFactory: (String) -> LlmBackend,
) : TopicAnalysisModelEvalUseCase() {
    override suspend fun runBundledKakaoAsset(models: List<String>?): TopicAnalysisModelEvalRunResult {
        val sourceName = "KakaoTalkChats.txt"
        val text = javaClass.classLoader.getResource("assets/$sourceName")
            ?.readText(Charsets.UTF_8)
            ?: error("Bundled asset not found: assets/$sourceName")
        return run(sourceName, text, models)
    }

    suspend fun run(
        sourceName: String,
        text: String,
        models: List<String>? = null,
    ): TopicAnalysisModelEvalRunResult {
        return withContext(Dispatchers.IO) {
            Files.createDirectories(outputDirectory)
            val document = kakaoDocument(sourceName, text)
            val evalModels = models?.takeIf { it.isNotEmpty() } ?: this@TopicAnalysisModelEvalService.models
            val entries = evalModels
                .map { model -> async { runModel(model, document) } }
                .awaitAll()
            val outputFile = outputDirectory.resolve("eval-${timestamp()}.txt")
            Files.writeString(outputFile, renderReport(sourceName, entries))
            TopicAnalysisModelEvalRunResult(
                outputPath = outputFile.toString().replace('\\', '/'),
                sourceName = sourceName,
                results = entries.map { entry ->
                    TopicAnalysisModelEvalResult(
                        model = entry.model,
                        parseSucceeded = entry.parseSucceeded,
                        errorMessage = entry.errorMessage,
                        rawResponseCount = entry.rawResponses.size,
                        promptTokens = entry.usage?.prompt_tokens,
                        completionTokens = entry.usage?.completion_tokens,
                        totalTokens = entry.usage?.total_tokens,
                    )
                },
            )

        }
    }

    private suspend fun runModel(
        model: String,
        document: SourceDocument,
    ): ModelEvalEntry {
        val backend = RecordingBackend(backendFactory(model))
        val analyzer = LlmTopicAnalyzer(backend, chunkSize = Int.MAX_VALUE)
        val errorMessage = try {
            analyzer.analyze(document)
            null
        } catch (error: Exception) {
            if (error is OpenRouterApiException) {
                backend.rawResponses += error.responseBody
            }
            error.message ?: error::class.simpleName ?: "Unknown error"
        }

        return ModelEvalEntry(
            model = model,
            parseSucceeded = errorMessage == null,
            errorMessage = errorMessage,
            rawResponses = backend.rawResponses.toList(),
            usage = backend.usage,
        )
    }

    private fun kakaoDocument(sourceName: String, text: String): SourceDocument {
        val messages = KakaoMessageParser.parse(sourceName, text)
        return SourceDocument(
            sourceType = "kakao",
            sourceName = sourceName,
            records = messages.mapIndexed { index, message ->
                val recordNumber = index + 1
                SourceRecord(
                    id = "r$recordNumber",
                    ref = recordNumber,
                    content = "${message.sender} | ${message.displayTime} | ${message.text}",
                )
            },
        )
    }

    private fun renderReport(sourceName: String, entries: List<ModelEvalEntry>): String =
        buildString {
            appendLine("sourceName=$sourceName")
            appendLine("createdAt=${Instant.now()}")
            appendLine("models=${entries.joinToString(",") { it.model }}")
            appendLine()
            entries.forEach { entry ->
                appendLine("================================================================================")
                appendLine("model=${entry.model}")
                appendLine("parseSucceeded=${entry.parseSucceeded}")
                appendLine("errorMessage=${entry.errorMessage ?: ""}")
                appendLine("promptTokens=${entry.usage?.prompt_tokens ?: ""}")
                appendLine("completionTokens=${entry.usage?.completion_tokens ?: ""}")
                appendLine("totalTokens=${entry.usage?.total_tokens ?: ""}")
                appendLine("rawResponseCount=${entry.rawResponses.size}")
                entry.rawResponses.forEachIndexed { index, raw ->
                    appendLine()
                    appendLine("---- rawResponse ${index + 1} ----")
                    appendLine(raw)
                }
                appendLine()
            }
        }

    private fun timestamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

    private data class ModelEvalEntry(
        val model: String,
        val parseSucceeded: Boolean,
        val errorMessage: String?,
        val rawResponses: List<String>,
        val usage: OpenRouterUsage?,
    )

    private class RecordingBackend(
        private val delegate: LlmBackend,
    ) : LlmBackend {
        val rawResponses = mutableListOf<String>()
        var usage: OpenRouterUsage? = null
            private set

        override suspend fun complete(
            system: String,
            messages: List<Message>,
            tools: List<Tool>,
            outputSchema: String,
        ): LlmResponse {
            val response = delegate.complete(system, messages, tools, outputSchema)
            rawResponses += when (response) {
                is LlmResponse.Text -> response.content
                is LlmResponse.ToolCall -> "TOOL_CALL: ${response.spec}"
            }
            usage = (delegate as? OpenRouterRawResponseHolder)
                ?.lastResponseBody
                ?.let { body ->
                    runCatching { OpenRouterResponseParser.parse(200, body).usage }.getOrNull()
                }
            return response
        }
    }

    companion object {
        val DEFAULT_MODELS = listOf(
            "deepseek/deepseek-v4-flash",
            "openai/gpt-5.1",
            "openai/gpt-5.2",
            "openai/gpt-5.4",
            "openai/gpt-5.5",
        )
    }
}
