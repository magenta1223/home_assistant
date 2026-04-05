package com.homeassistant.nlp.pipeline

import com.homeassistant.core.commands.ICommandExecutor
import com.homeassistant.core.models.CommandResult

class NoOpCommandExecutor : ICommandExecutor {
    override suspend fun execute(command: String, params: String, userId: String): CommandResult =
        CommandResult(text = null)
}
