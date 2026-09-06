package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.port.output.memory.placement.MemoryPlacementExtractor
import com.homeassistant.codex.completion.CodexCompletionClientFactory

object MemoryPlacementExtractorFactory {
    fun create(): MemoryPlacementExtractor = MemoryPlacementExtractor(CodexCompletionClientFactory.create())
}
