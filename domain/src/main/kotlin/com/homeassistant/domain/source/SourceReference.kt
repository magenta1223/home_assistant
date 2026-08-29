package com.homeassistant.domain.source

import java.security.MessageDigest

/** Immutable binary source attached to one or more interpreted source records. */
class SourceReferenceDraft(
    val fileName: String,
    val mediaType: String,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    val size: Int = content.size
    val sha256: String = sha256(content)

    init {
        require(fileName.isNotBlank()) { "reference fileName is required" }
        require(mediaType.isNotBlank()) { "reference mediaType is required" }
        require(content.isNotEmpty()) { "reference content is required" }
        require(content.size <= MAX_BYTES) { "reference must be 20MB or smaller" }
    }

    fun bytes(): ByteArray = content.copyOf()

    companion object {
        const val MAX_BYTES: Int = 20 * 1024 * 1024
    }
}

/** Persisted reference metadata; binary content remains behind the repository boundary. */
data class SourceReference(
    val id: Int,
    val fileName: String,
    val mediaType: String,
    val size: Int,
    val sha256: String,
)

class InvalidSourceReferenceException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
