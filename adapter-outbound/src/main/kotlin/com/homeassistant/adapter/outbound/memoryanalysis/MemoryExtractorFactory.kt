package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.codex.completion.CodexCompletionClientFactory

object MemoryExtractorFactory {
    fun create(): MemoryExtractor = CodexMemoryExtractor(CodexCompletionClientFactory.create())
}
