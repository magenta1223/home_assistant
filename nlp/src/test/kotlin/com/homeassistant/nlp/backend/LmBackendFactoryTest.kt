package com.homeassistant.nlp.backend

import com.homeassistant.nlp.backend.codex.CodexCliBackend
import kotlin.test.Test
import kotlin.test.assertIs

class LmBackendFactoryTest {
    @Test
    fun `creates the codex backend`() {
        assertIs<CodexCliBackend>(LmBackendFactory.create())
    }
}
