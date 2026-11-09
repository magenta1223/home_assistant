package com.homeassistant.llm

import com.homeassistant.infrastructure.runtime.Runtime

/**
 * Holds multi-turn conversations with the selected agent backend while it is running. Conversation ids are chosen
 * by the backend.
 */
interface ChatClient : Runtime {
    /** Opens a conversation and returns its id. */
    fun openChat(): Result<String>

    /**
     * Sends one [prompt] to [chatId] and returns the answer; a failure's message names its category. With
     * [toolServer] the agent may call that server's tools during the turn and no other tool.
     */
    fun send(chatId: String, prompt: String, toolServer: McpToolServer? = null): Result<String>

    /** Closes [chatId], releasing what this client holds for it. */
    fun closeChat(chatId: String)
}