package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.application.memory.analysis.MemoryExtractor
import com.homeassistant.adapter.outbound.codex.CodexCliClient

object MemoryExtractorFactory {
    fun create(): MemoryExtractor = CodexMemoryExtractor(CodexCliClient())
}
