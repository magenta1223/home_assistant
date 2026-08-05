package com.homeassistant.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceModelsTest {
    @Test
    fun `source document preserves ordered source records`() {
        val records = listOf(
            SourceRecord(id = "r1", ref = 1, content = "first"),
            SourceRecord(id = "r2", ref = 2, content = "second"),
        )

        val document = SourceDocument(
            sourceType = "kakao",
            sourceName = "family.txt",
            records = records,
        )

        assertEquals("kakao", document.sourceType)
        assertEquals("family.txt", document.sourceName)
        assertEquals(records, document.records)
    }
}
