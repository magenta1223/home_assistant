package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.codex.completion.CompletionClient
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Runs Codex memory analysis for normalized source documents. */
internal class CodexMemoryExtractor(
    private val client: CompletionClient,
    private val chunkSize: Int = CHUNK_SIZE,
    private val chunkOverlap: Int = minOf(CHUNK_OVERLAP, chunkSize - 1),
    private val maxConcurrentChunks: Int = MAX_CONCURRENT_CHUNKS,
    private val maxMergeInputChars: Int = MAX_MERGE_INPUT_CHARS,
) : MemoryExtractor {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(chunkOverlap in 0 until chunkSize) { "chunkOverlap must be between zero and chunkSize" }
        require(maxConcurrentChunks > 0) { "maxConcurrentChunks must be positive" }
        require(maxMergeInputChars > 0) { "maxMergeInputChars must be positive" }
    }

    override suspend fun analyze(document: SourceDocument): List<MemoryProposal> {
        if (document.records.isEmpty()) return emptyList()
        val memories = if (document.records.size <= chunkSize) {
            analyzeChunk(document)
        } else {
            val semaphore = Semaphore(maxConcurrentChunks)
            val chunkMemories = coroutineScope {
                chunkDocument(document)
                    .map { chunk -> async { semaphore.withPermit { analyzeChunk(chunk) } } }
                    .awaitAll()
                    .flatten()
            }
            mergeMemories(document, chunkMemories)
        }
        return memories.distinctByMeaningAndEvidence()
    }

    private suspend fun analyzeChunk(document: SourceDocument): List<MemoryProposal> =
        requestMemories(
            document = document,
            system = MemoryAnalysisPrompt.system(),
            userMessage = renderDocument(document),
        )

    private suspend fun mergeMemories(
        document: SourceDocument,
        chunkMemories: List<MemoryProposal>,
    ): List<MemoryProposal> {
        if (chunkMemories.isEmpty()) return emptyList()
        val mergeInput = renderMemoryProposals(document, chunkMemories)
        if (mergeInput.length > maxMergeInputChars) return chunkMemories.distinctByMeaningAndEvidence()
        return requestMemories(
            document = document,
            system = MemoryAnalysisPrompt.mergeSystem(),
            userMessage = mergeInput,
        )
    }

    private suspend fun requestMemories(
        document: SourceDocument,
        system: String,
        userMessage: String,
    ): List<MemoryProposal> {
        val raw = client.complete(
            system = system,
            userMessage = userMessage,
            outputSchema = MemoryAnalysisOutputContract.schema,
        )
        val response = MemoryAnalysisOutputContract.decode(raw)
        return response.memories.map { memory ->
            val evidence = parseEvidence(document, memory.evidenceRecordIds)
            require(memory.text.isNotBlank()) { "Memory text must not be blank" }
            require(memory.subject.isNotBlank()) { "Memory subject must not be blank" }
            require(evidence.isNotEmpty()) { "Memory must include at least one evidence record" }
            MemoryProposal(
                content = memory.text.trim(),
                subject = memory.subject.trim(),
                memoryType = memory.memoryType,
                certainty = memory.certainty,
                evidenceIds = evidence.map { it.id },
            )
        }
    }

    private fun chunkDocument(document: SourceDocument): List<SourceDocument> {
        val step = chunkSize - chunkOverlap
        return generateSequence(0) { it + step }
            .takeWhile { start ->
                start < document.records.size && (start == 0 || start + chunkOverlap < document.records.size)
            }
            .mapIndexed { index, start ->
                document.copy(
                    contextRecords = if (index == 0) document.contextRecords else emptyList(),
                    records = document.records.subList(start, minOf(start + chunkSize, document.records.size)),
                )
            }
            .toList()
    }

    private fun parseEvidence(document: SourceDocument, evidenceRecordIds: List<String>): List<SourceRecord> =
        evidenceRecordIds.map { recordId ->
            document.records.firstOrNull { it.promptId == recordId }
                ?: throw IllegalArgumentException("Unknown evidence record id: $recordId")
        }.distinctBy { it.id }

    private fun renderDocument(document: SourceDocument): String = buildString {
        if (document.contextRecords.isNotEmpty()) {
            appendLine("[CONTEXT_ONLY]")
            document.contextRecords.forEach { appendLine("${it.contextPromptId} | ${it.content}") }
        }
        appendLine("[NEW_RECORDS]")
        append(document.records.joinToString("\n") { "${it.promptId} | ${it.content}" })
    }

    private fun renderMemoryProposals(
        document: SourceDocument,
        memories: List<MemoryProposal>,
    ): String = MemoryAnalysisLlmResponse(
        memories = memories.map { memory ->
            MemoryLlmResponse(
                text = memory.content,
                subject = memory.subject,
                memoryType = memory.memoryType,
                certainty = memory.certainty,
                evidenceRecordIds = memory.evidenceIds.map { evidenceId ->
                    document.records.first { it.id == evidenceId }.promptId
                },
            )
        },
    ).encodeToString()

    private companion object {
        const val CHUNK_SIZE = 400
        const val CHUNK_OVERLAP = 20
        const val MAX_CONCURRENT_CHUNKS = 4
        const val MAX_MERGE_INPUT_CHARS = 100_000
    }
}

private val SourceRecord.promptId: String
    get() = "r$id"

private val SourceRecord.contextPromptId: String
    get() = "c$id"

private fun List<MemoryProposal>.distinctByMeaningAndEvidence(): List<MemoryProposal> =
    distinctBy { memory -> memory.content to memory.evidenceIds.toSet() }
