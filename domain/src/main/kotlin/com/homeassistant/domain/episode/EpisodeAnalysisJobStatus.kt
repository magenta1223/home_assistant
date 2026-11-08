package com.homeassistant.domain.episode

/** Where a submitted episode analysis is in its lifecycle. A [FAILED] analysis is retried until its attempt limit is reached. */
enum class EpisodeAnalysisJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }
