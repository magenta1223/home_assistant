package com.homeassistant.adapter.inbound.mcp

import com.homeassistant.application.port.inbound.contexttool.ContextAccess
import com.homeassistant.application.port.inbound.contexttool.FindTransactions
import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.port.inbound.contexttool.TransactionSearch
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Serves the context tools an agent calls while it answers, over MCP Streamable HTTP at
 * [AppConfig.ROUTE_CONTEXT_TOOLS]. Each request is answered statelessly as the user its bearer context token was
 * issued for, so the agent cannot name a user; a request without a valid token is rejected with 401 before any MCP
 * message is read.
 */
fun Application.contextToolServer(
    contextAccess: ContextAccess,
    searchMemory: SearchMemory,
    findTransactions: FindTransactions,
) {
    authenticateContextOwner(contextAccess)
    mcpStatelessStreamableHttp(path = AppConfig.ROUTE_CONTEXT_TOOLS) {
        toolsFor(call.attributes[CONTEXT_OWNER], searchMemory, findTransactions)
    }
}

/**
 * Resolves the owner of each context tool request's bearer token and keeps it on the call. The MCP SDK serves its own
 * route, so the check runs as a call interceptor instead of a Ktor authentication provider.
 */
private fun Application.authenticateContextOwner(contextAccess: ContextAccess) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (context.request.path() != AppConfig.ROUTE_CONTEXT_TOOLS) return@intercept
        val contextToken = context.request.headers[HttpHeaders.Authorization]?.removePrefix(BEARER)
        val owner = contextToken?.let(contextAccess::ownerOf)
        if (owner == null) {
            context.respond(HttpStatusCode.Unauthorized)
            finish()
            return@intercept
        }
        context.attributes.put(CONTEXT_OWNER, owner)
    }
}

/** The tools answering as [owner]. */
private fun toolsFor(owner: UserId, searchMemory: SearchMemory, findTransactions: FindTransactions): Server =
    Server(
        Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ) {
        addTool(
            name = SEARCH_MEMORY,
            description = "Searches the household's stored memory (long-term facts and saved episodes) the user " +
                "may read. Returns matching facts and episodes with the time each episode was saved.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { string(QUERY, "What to look for, in the user's language.") },
                required = listOf(QUERY),
            ),
        ) { request ->
            val query = request.stringArgument(QUERY)
                ?: return@addTool toolError("$QUERY is required")
            textResult { searchMemory.search(owner, query) }
        }
        addTool(
            name = FIND_TRANSACTIONS,
            description = "Lists the user's household finance transactions (card payments, income, transfers) " +
                "in a date range with their total per currency. Spending amounts are negative.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    string(FROM, "First day, inclusive, as YYYY-MM-DD.")
                    string(TO, "Last day, inclusive, as YYYY-MM-DD.")
                    string(CATEGORY, "Optional. Only transactions whose major or minor category is exactly this.")
                    string(MERCHANT, "Optional. Only transactions whose description contains this text.")
                },
                required = listOf(FROM, TO),
            ),
        ) { request ->
            val search = try {
                request.transactionSearch()
            } catch (error: IllegalArgumentException) {
                return@addTool toolError(error.message ?: "invalid arguments")
            }
            textResult { findTransactions.find(owner, search) }
        }
    }

/**
 * The search the arguments of [this] request describe.
 *
 * @throws IllegalArgumentException when a date is missing or malformed, or the range is reversed.
 */
private fun CallToolRequest.transactionSearch(): TransactionSearch =
    TransactionSearch(
        from = dateArgument(FROM),
        to = dateArgument(TO),
        category = stringArgument(CATEGORY),
        merchant = stringArgument(MERCHANT),
    )

private fun CallToolRequest.dateArgument(name: String): LocalDate {
    val value = requireNotNull(stringArgument(name)) { "$name is required" }
    return try {
        LocalDate.parse(value)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("$name must be a date as YYYY-MM-DD", error)
    }
}

/** Runs a blocking [lookup] off the request thread and returns its text. */
private suspend fun textResult(lookup: () -> String): CallToolResult =
    CallToolResult(content = listOf(TextContent(withContext(Dispatchers.IO) { lookup() })))

private fun CallToolRequest.stringArgument(name: String): String? =
    arguments?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObjectBuilder.string(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun toolError(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

private val CONTEXT_OWNER = AttributeKey<UserId>("ContextOwner")
private const val BEARER = "Bearer "
private const val SERVER_NAME = "home"
private const val SERVER_VERSION = "1"
private const val SEARCH_MEMORY = "search_memory"
private const val QUERY = "query"
private const val FIND_TRANSACTIONS = "find_transactions"
private const val FROM = "from"
private const val TO = "to"
private const val CATEGORY = "category"
private const val MERCHANT = "merchant"
