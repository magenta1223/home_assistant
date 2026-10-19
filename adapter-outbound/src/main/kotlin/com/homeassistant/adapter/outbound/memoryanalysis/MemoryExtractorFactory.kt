package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.llm.completion.CompletionClient

object MemoryExtractorFactory {
    fun create(client: CompletionClient): MemoryExtractor = MemoryExtractor(client)
}
