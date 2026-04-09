package com.homeassistant.core.commands

import com.homeassistant.core.models.CommandResult

interface ICommandExecutor {
    suspend fun execute(command: CommandName, params: CommandParams, userId: UserId): CommandResult
}
