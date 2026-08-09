package com.homeassistant.app.memory

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MemoryReindexTest {
    @Test
    fun `missing database is rejected without creating an empty file`() {
        val directory = Files.createTempDirectory("missing-reindex-db")
        val missing = directory.resolve("mistyped.sqlite")
        try {
            assertFailsWith<IllegalArgumentException> {
                requireExistingDatabase(missing.toString())
            }
            assertFalse(Files.exists(missing))
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `existing database file is resolved before reindex initialization`() {
        val database = Files.createTempFile("existing-reindex-db", ".sqlite")
        try {
            assertEquals(database.toAbsolutePath().normalize(), requireExistingDatabase(database.toString()))
        } finally {
            Files.deleteIfExists(database)
        }
    }
}
