package com.homeassistant.application.port.outbound.embedding

data class ComponentEmbeddingKey(val componentId: Int, val version: String, val space: EmbeddingSpace) {
    init { require(componentId > 0 && version.isNotBlank()) }

    companion object {
        /** The key a Component's vector is cached under in [space]; a statement never changes, so its fingerprint is the version. */
        fun of(componentId: Int, statement: String, space: EmbeddingSpace) =
            ComponentEmbeddingKey(componentId, contentFingerprint(statement), space)
    }
}
