package com.homeassistant.application.port.outbound.memoryextraction.component

import com.homeassistant.domain.access.AccessControl

/**
 * An episode whose Components are not extracted yet, with the messages it was written from, oldest first.
 */
data class PendingEpisode(
    val id: Int,
    val title: String,
    val content: String,
    val access: AccessControl,
    val messages: List<String>,
) {
    init { require(id > 0 && title.isNotBlank() && content.isNotBlank() && messages.isNotEmpty()) }
}
