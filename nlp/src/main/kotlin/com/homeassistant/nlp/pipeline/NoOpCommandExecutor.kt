package com.homeassistant.nlp.pipeline

import com.homeassistant.core.commands.CommandName
import com.homeassistant.core.commands.CommandParams
import com.homeassistant.core.commands.ICommandExecutor
import com.homeassistant.core.commands.UserId
import com.homeassistant.core.models.CommandResult

class NoOpCommandExecutor : ICommandExecutor {
    override suspend fun execute(command: CommandName, params: CommandParams, userId: UserId): CommandResult =
        CommandResult(text = null)
}
