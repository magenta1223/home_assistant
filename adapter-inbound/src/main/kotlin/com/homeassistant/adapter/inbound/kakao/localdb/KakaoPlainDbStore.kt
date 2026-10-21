package com.homeassistant.adapter.inbound.kakao.localdb

import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The only schema-dependent code of the Kakao local DB sync: it reads the plaintext SQLite that an external
 * tool decrypts into [dbPath]. The schema is not known yet, so it reads nothing; once it is, only this class
 * maps the tables and columns to [KakaoLocalMessage].
 */
class KakaoPlainDbStore(private val dbPath: Path) : KakaoLocalStore {
    init {
        log.warn("Kakao local DB schema is not mapped yet; {} is not read", dbPath)
    }

    override fun messagesAfter(chatId: String, afterLogId: Long, limit: Int): List<KakaoLocalMessage> = emptyList()

    private companion object {
        val log = LoggerFactory.getLogger(KakaoPlainDbStore::class.java)
    }
}
