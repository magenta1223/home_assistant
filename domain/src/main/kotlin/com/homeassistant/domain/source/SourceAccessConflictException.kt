package com.homeassistant.domain.source

import com.homeassistant.domain.memory.MemoryAccess

class SourceAccessConflictException(
    val sourceName: String,
    val existingAccess: MemoryAccess,
) : RuntimeException("Source records already exist with a different access scope: $sourceName")
