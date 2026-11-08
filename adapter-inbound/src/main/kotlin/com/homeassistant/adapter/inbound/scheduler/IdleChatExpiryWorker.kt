package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.chatservice.ExpireIdleChats
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [ExpireIdleChats] every [EXPIRY_CHECK_SECONDS].
 */
class IdleChatExpiryWorker(
    private val expireIdle: ExpireIdleChats,
) : AbstractRuntime() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "episode-conversation-expiry").apply { isDaemon = true }
    }

    override fun onStart() {
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

    override fun onStop() {
        executor.shutdownNow()
    }

    private companion object {
        const val EXPIRY_CHECK_SECONDS = 1L
    }
}
