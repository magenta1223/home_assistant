package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.slackconversation.SlackPrincipal
import com.homeassistant.application.port.output.slackconversation.SlackPrincipalResolver
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable
data class SlackMemberScopeConfig(
    val teamId: String,
    val slackUserId: String,
    val userId: String,
)

/** Maps Slack identities to application principals and exposes access policy. */
interface SlackIdentityDirectory : SlackPrincipalResolver {
    /** Provides the access policy derived from configured Slack members. */
    val accessPolicy: HouseholdAccessPolicy

    /** Resolves a configured Slack actor to its application principal. */
    override fun resolve(teamId: String?, slackUserId: String?): SlackPrincipal?
}

private class ConfiguredSlackIdentityDirectory(
    private val principals: Map<SlackActor, SlackPrincipal>,
) : SlackIdentityDirectory {
    override val accessPolicy: HouseholdAccessPolicy =
        HouseholdAccessPolicies.fixed(principals.values.map { it.userId })

    override fun resolve(teamId: String?, slackUserId: String?): SlackPrincipal? {
        if (teamId.isNullOrBlank() || slackUserId.isNullOrBlank()) return null
        return principals[SlackActor(teamId, slackUserId)]
    }
}

object SlackIdentityDirectoryFactory {
    fun fromJson(
        configuredTeamId: String,
        json: String,
    ): SlackIdentityDirectory {
        require(configuredTeamId.isNotBlank()) { "SLACK_TEAM_ID is required" }
        val records = JsonSerializer.json.decodeFromString<List<SlackMemberScopeConfig>>(json)
        require(records.isNotEmpty()) { "SLACK_MEMBER_SCOPES_JSON must not be empty" }

        val principals = LinkedHashMap<SlackActor, SlackPrincipal>()
        records.forEach { record ->
            require(record.teamId == configuredTeamId) {
                "Slack member mapping team does not match SLACK_TEAM_ID"
            }
            val principal = SlackPrincipal(
                teamId = record.teamId,
                slackUserId = record.slackUserId,
                userId = UserId(record.userId),
            )
            val previous = principals.put(
                SlackActor(record.teamId, record.slackUserId),
                principal,
            )
            require(previous == null) { "Duplicate Slack member mapping" }
        }
        return ConfiguredSlackIdentityDirectory(principals)
    }
}

private data class SlackActor(
    val teamId: String,
    val slackUserId: String,
)
