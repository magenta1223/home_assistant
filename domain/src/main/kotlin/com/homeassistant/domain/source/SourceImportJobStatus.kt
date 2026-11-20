package com.homeassistant.domain.source

/** Where a submitted source import job is in its lifecycle. A [FAILED] job is retried until its attempt limit is reached. */
enum class SourceImportJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }
