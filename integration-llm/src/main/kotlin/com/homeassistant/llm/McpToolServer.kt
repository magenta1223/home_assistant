package com.homeassistant.llm

/**
 * An MCP server reached over Streamable HTTP at [url] whose tools a chat turn may call, authenticated with
 * [bearerToken]. The agent sees its tools under [name].
 */
data class McpToolServer(
    val name: String,
    val url: String,
    val bearerToken: String,
)
