package com.homeassistant.app.services

import com.homeassistant.application.usecase.chatservice.ExpireIdleChats
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface ConversationExpiryWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : ConversationExpiryWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

internal class EpisodeConversationExpiryWorker(
    private val expireIdle: ExpireIdleChats,
) : ConversationExpiryWorker {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "episode-conversation-expiry").apply { isDaemon = true }
    }

    override fun start() {
        executor.scheduleWithFixedDelay(
            {
                runCatching(expireIdle::execute)
                    .onFailure {
                        log.warn("Episode conversation expiry failed category={}", it.javaClass.simpleName)
                    }
            },
            0,
            EXPIRY_CHECK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val EXPIRY_CHECK_SECONDS = 1L
    }
}
