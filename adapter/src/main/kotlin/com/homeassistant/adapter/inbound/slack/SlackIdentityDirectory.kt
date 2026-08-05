package com.homeassistant.adapter.inbound.slack

import com.homeassistant.core.identity.HouseholdAccessPolicy
import com.homeassistant.core.identity.HouseholdAccessPolicies
import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.domain.slackconversation.SlackPrincipal
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable
data class SlackMemberScopeConfig(
    val teamId: String,
    val slackUserId: String,
    val userId: String,
    val familyId: String,
)

interface SlackIdentityDirectory {
    val accessPolicy: HouseholdAccessPolicy
    fun resolve(teamId: String?, slackUserId: String?): SlackPrincipal?
}

private class ConfiguredSlackIdentityDirectory(
    private val principals: Map<SlackActor, SlackPrincipal>,
) : SlackIdentityDirectory {
    override val accessPolicy: HouseholdAccessPolicy =
        HouseholdAccessPolicies.fixed(principals.values.map { it.scope })

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
                    userId = record.userId,
                    familyId = record.familyId,
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
