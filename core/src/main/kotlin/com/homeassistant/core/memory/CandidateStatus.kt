package com.homeassistant.core.memory

/** Tracks the review state of machine-generated candidates before they become confirmed memory. */
enum class CandidateStatus { PENDING, APPROVED, REJECTED }
