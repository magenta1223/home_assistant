package com.homeassistant.application.port.outbound.embedding

import com.homeassistant.domain.embedding.EmbeddingVector
import java.security.MessageDigest

/** A stored [vector] of the shared embedding space, identified by the source-specific [key] it was generated for. */
data class Embedding<K>(val key: K, val vector: EmbeddingVector)

/** The SHA-256 hex of [description], identifying the exact text a vector was generated from. */
fun descriptionFingerprint(description: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(description.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
