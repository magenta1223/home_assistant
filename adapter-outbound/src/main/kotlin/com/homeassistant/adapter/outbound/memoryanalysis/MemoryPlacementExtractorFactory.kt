package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCliClient
import com.homeassistant.application.memory.tree.MemoryPlacementExtractor

object MemoryPlacementExtractorFactory {
    fun create(): MemoryPlacementExtractor = CodexMemoryPlacementExtractor(CodexCliClient())
}
