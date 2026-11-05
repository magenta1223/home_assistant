package com.homeassistant.domain.source

import com.homeassistant.domain.episode.EpisodeAccess

class SourceAccessConflictException(
    val sourceName: String,
    val existingAccess: EpisodeAccess,
) : RuntimeException("Source records already exist with a different access scope: $sourceName")
