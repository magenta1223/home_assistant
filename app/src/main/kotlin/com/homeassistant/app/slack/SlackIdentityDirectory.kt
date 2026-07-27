package com.homeassistant.app.slack

import com.homeassistant.core.identity.FixedHouseholdAccessPolicy
import com.homeassistant.core.identity.HouseholdAccessPolicy
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

class SlackIdentityDirectory private constructor(
    private val principals: Map<SlackActor, SlackPrincipal>,
) {
    val accessPolicy: HouseholdAccessPolicy =
        FixedHouseholdAccessPolicy(principals.values.map { it.scope })

    fun resolve(teamId: String?, slackUserId: String?): SlackPrincipal? {
        if (teamId.isNullOrBlank() || slackUserId.isNullOrBlank()) return null
        return principals[SlackActor(teamId, slackUserId)]
    }

    companion object {
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
            return SlackIdentityDirectory(principals)
        }
    }
}

private data class SlackActor(
    val teamId: String,
    val slackUserId: String,
)
