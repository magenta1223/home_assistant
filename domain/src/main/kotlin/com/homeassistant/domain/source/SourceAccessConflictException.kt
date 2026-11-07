package com.homeassistant.domain.source

import com.homeassistant.domain.access.AccessControl

class SourceAccessConflictException(
    val sourceName: String,
    val existingAccess: AccessControl,
) : RuntimeException("Source records already exist with a different access scope: $sourceName")
