package com.homeassistant.application.port.output.memory.analysis

import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDocument

/** Converts a source document into flat, evidence-backed atomic memories. */
interface MemoryExtractor {
    /** Extracts atomic memory proposals from the supplied source document. */
    suspend fun analyze(document: SourceDocument): List<MemoryProposal>
}
