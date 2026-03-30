package com.homeassistant.core.commands

import com.homeassistant.core.models.CommandResult

/**
 * Command Executor
 * */
interface ICommandExecutor {
    suspend fun execute(command: String, params: String, userId: String): CommandResult
}
