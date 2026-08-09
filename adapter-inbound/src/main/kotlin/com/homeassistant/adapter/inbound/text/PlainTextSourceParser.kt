package com.homeassistant.adapter.inbound.text

import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import java.security.MessageDigest

/** Converts one explicitly entered knowledge document into a source record. */
object PlainTextSourceParser {
    fun parse(sourceName: String, text: String): SourceDocumentDraft {
        val content = text.trim()
        require(content.isNotEmpty()) { "text is required" }
        return SourceDocumentDraft(
            source = SourceDescriptor(type = "text", name = sourceName),
            records = listOf(
                SourceRecordDraft(
                    deduplicationKey = sha256(content),
                    content = content,
                ),
            ),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
