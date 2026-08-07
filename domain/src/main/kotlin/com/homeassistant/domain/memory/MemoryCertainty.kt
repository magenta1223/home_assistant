package com.homeassistant.domain.memory

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCertainty { OBSERVED, SAID, INFERRED, UNCERTAIN }
