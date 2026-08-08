package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCliClient
import com.homeassistant.application.port.output.memory.placement.MemoryPlacementExtractor

object MemoryPlacementExtractorFactory {
    fun create(): MemoryPlacementExtractor = CodexMemoryPlacementExtractor(CodexCliClient())
}
