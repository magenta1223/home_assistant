package com.homeassistant.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceModelsTest {
    @Test
    fun `source document preserves ordered source records`() {
        val records = listOf(
            sourceRecord(1, "first"),
            sourceRecord(2, "second"),
        )

        val document = SourceDocument(
            source = SourceDescriptor("kakao", "family.txt"),
            records = records,
        )

        assertEquals("kakao", document.source.type)
        assertEquals("family.txt", document.source.name)
        assertEquals(records, document.records)
    }

    private fun sourceRecord(id: Int, content: String) =
        SourceRecord(
            id = id,
            deduplicationKey = "key-$id",
            content = content,
        )
}
