package com.homeassistant.adapter.outbound.topic

import com.homeassistant.application.port.output.topic.TopicPlacementProposer
import com.homeassistant.codex.completion.CodexCompletionClientFactory

object TopicPlacementProposerFactory {
    fun create(): TopicPlacementProposer = CodexTopicPlacementProposer(CodexCompletionClientFactory.create())
}
