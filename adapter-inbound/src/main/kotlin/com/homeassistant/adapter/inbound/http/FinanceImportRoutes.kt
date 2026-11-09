package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.banksalad.BanksaladLedgerParser
import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.configuration.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * Imports a Banksalad xlsx export as the ledger of the authenticated user and answers how many transactions were
 * saved. Re-importing an export saves the same transactions again with their latest type, categories and memo.
 */
internal fun Route.financeImportRoutes(importFinanceTransactions: ImportFinanceTransactions?) {
    post(AppConfig.ROUTE_FINANCE_IMPORT) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        if (importFinanceTransactions == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "finance import unavailable"))
            return@post
        }
        val request = call.receive<FinanceImportRequest>()
        val transactions = try {
            withContext(Dispatchers.IO) { BanksaladLedgerParser.parse(principal.userId, request.workbook()) }
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid finance export")))
            return@post
        }
        val savedCount = withContext(Dispatchers.IO) { importFinanceTransactions.import(transactions) }
        call.respond(HttpStatusCode.OK, FinanceImportResponse(savedCount))
    }
}

@Serializable
internal data class FinanceImportRequest(val contentBase64: String) {
    /** The decoded xlsx bytes. */
    fun workbook(): ByteArray {
        require(contentBase64.length <= MAX_EXPORT_BASE64_LENGTH) { "the export must be 20MB or smaller" }
        return try {
            Base64.getDecoder().decode(contentBase64)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("contentBase64 is invalid", error)
        }
    }
}

private const val MAX_EXPORT_BYTES = 20 * 1024 * 1024
private const val MAX_EXPORT_BASE64_LENGTH = ((MAX_EXPORT_BYTES + 2) / 3) * 4

@Serializable
private data class FinanceImportResponse(val savedTransactionCount: Int)
