package com.homeassistant.application.memory

import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.source.SourceDescriptor

data class MemoryContext(
    val memory: Memory,
    val topic: MemoryTopicContext? = null,
)

data class MemoryTopicContext(
    val id: Int,
    val title: String,
    val summary: String,
    val source: SourceDescriptor,
)
