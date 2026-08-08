package com.homeassistant.application.port.input.slackconversation

import com.homeassistant.domain.identity.UserId

data class SlackPrincipal(
    val teamId: String,
    val slackUserId: String,
    val userId: UserId,
) {
    init {
        require(teamId.isNotBlank()) { "teamId is required" }
        require(slackUserId.isNotBlank()) { "slackUserId is required" }
    }
}
