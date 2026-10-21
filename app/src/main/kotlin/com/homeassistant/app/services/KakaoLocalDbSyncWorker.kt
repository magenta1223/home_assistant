package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.kakao.localdb.KakaoChatSyncResult
import com.homeassistant.adapter.inbound.kakao.localdb.KakaoLocalDbSync
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal interface KakaoSyncWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : KakaoSyncWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

/**
 * Polls the Kakao local DB. The DB is replaced by an external tool on its own schedule, so polling more often
 * than that only finds nothing new.
 */
internal class KakaoLocalDbSyncWorker(
    private val sync: KakaoLocalDbSync,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "kakao-local-db-sync").apply { isDaemon = true }
    },
) : KakaoSyncWorker {
    override fun start() {
        executor.scheduleWithFixedDelay(::runRound, 0, intervalMinutes, TimeUnit.MINUTES)
    }

    /** Contains every failure: an exception escaping a scheduled task would cancel later rounds. */
    internal fun runRound() {
        runCatching { runBlocking { sync.syncOnce() } }
            .onSuccess { results -> results.forEach(::report) }
            .onFailure { log.warn("Kakao local DB sync round failed", it) }
    }

    private fun report(result: KakaoChatSyncResult) = when (result) {
        is KakaoChatSyncResult.UpToDate -> if (result.syncedMessageCount > 0) {
            log.info("Kakao chat {} synced messages={}", result.chatId, result.syncedMessageCount)
        } else {
            Unit
        }
        is KakaoChatSyncResult.Stopped -> log.warn(
            "Kakao chat {} sync stopped after messages={}; retrying next round",
            result.chatId,
            result.syncedMessageCount,
            result.cause,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_INTERVAL_MINUTES = 10L
        val log = LoggerFactory.getLogger(KakaoLocalDbSyncWorker::class.java)
    }
}
