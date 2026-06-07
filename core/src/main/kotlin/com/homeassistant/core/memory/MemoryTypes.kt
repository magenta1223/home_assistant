package com.homeassistant.core.memory

/** Classifies what kind of long-lived memory a topic or memory candidate represents. */
enum class MemoryType { FACT, EVENT, COMMITMENT, PREFERENCE, DECISION }

/** Tracks the review state of machine-generated candidates before they become confirmed memory. */
enum class CandidateStatus { PENDING, APPROVED, REJECTED }
