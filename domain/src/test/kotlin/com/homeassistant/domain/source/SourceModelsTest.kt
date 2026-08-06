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
            sourceType = "kakao",
            sourceName = "family.txt",
            records = records,
        )

        assertEquals("kakao", document.sourceType)
        assertEquals("family.txt", document.sourceName)
        assertEquals(records, document.records)
        assertEquals("r1", document.records.first().promptId)
    }

    private fun sourceRecord(id: Int, content: String) =
        SourceRecord(
            id = id,
            sourceType = "kakao",
            sourceName = "family.txt",
            deduplicationKey = "key-$id",
            content = content,
        )
}
