package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.household.AlreadyInHouseholdException
import com.homeassistant.application.port.inbound.household.Households
import com.homeassistant.application.port.inbound.household.InvalidInvitationException
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Lets the authenticated user see their household, issue a code that invites someone into it, and join a household
 * with a code.
 */
internal fun Route.householdRoutes(households: Households?) {
    get(AppConfig.ROUTE_HOUSEHOLD_MEMBERS) {
        call.answerFor(households) { userId, available ->
            val members = withContext(Dispatchers.IO) { available.membersOf(userId) }
            call.respond(HttpStatusCode.OK, members.toResponse())
        }
    }
    post(AppConfig.ROUTE_HOUSEHOLD_INVITATIONS) {
        call.answerFor(households) { userId, available ->
            val invitation = withContext(Dispatchers.IO) { available.invite(userId) }
            call.respond(
                HttpStatusCode.Created,
                HouseholdInvitationResponse(invitation.code, invitation.expiresAt.toString()),
            )
        }
    }
    post(AppConfig.ROUTE_HOUSEHOLD_JOIN) {
        call.answerFor(households) { userId, available ->
            val request = try {
                call.receive<HouseholdJoinRequest>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
                return@answerFor
            }
            try {
                val members = withContext(Dispatchers.IO) { available.join(userId, request.code) }
                call.respond(HttpStatusCode.OK, members.toResponse())
            } catch (error: InvalidInvitationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to error.message))
            } catch (error: AlreadyInHouseholdException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to error.message))
            }
        }
    }
}

/** Runs [answer] for the authenticated caller; answers 401 without a caller and 503 without [households]. */
private suspend fun ApplicationCall.answerFor(
    households: Households?,
    answer: suspend (UserId, Households) -> Unit,
) {
    val principal = principal<HttpUserPrincipal>()
    when {
        principal == null -> respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
        households == null -> respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "households unavailable"))
        else -> answer(principal.userId, households)
    }
}

private fun List<RegisteredUser>.toResponse() = HouseholdMembersResponse(map { it.displayName })

@Serializable
private data class HouseholdMembersResponse(val members: List<String>)

@Serializable
private data class HouseholdInvitationResponse(val code: String, val expiresAt: String)

@Serializable
private data class HouseholdJoinRequest(val code: String)
