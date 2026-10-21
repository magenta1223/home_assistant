package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** The chats to collect from the Kakao local DB. Collection is opt-in: an unlisted chat is never read. */
data class KakaoLocalDbConfig(
    val dbPath: Path,
    val cursorPath: Path,
    val ownerUserId: UserId,
    val chats: List<KakaoChatSource>,
) {
    companion object {
        /** No chat allowlist means the sync is disabled. */
        fun fromEnv(readEnv: (String) -> String? = { Env[it] }): KakaoLocalDbConfig? {
            val chatsJson = readEnv(AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val ownerUserId = requireNotNull(readEnv(AppConfig.ENV_VAR_KAKAO_LOCAL_DB_OWNER_USER_ID)?.takeIf(String::isNotBlank)) {
                "${AppConfig.ENV_VAR_KAKAO_LOCAL_DB_OWNER_USER_ID} is required when ${AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON} is set"
            }
            val dbPath = readEnv(AppConfig.ENV_VAR_KAKAO_LOCAL_DB_PATH)?.takeIf(String::isNotBlank)
                ?: AppConfig.DEFAULT_KAKAO_LOCAL_DB_PATH
            return KakaoLocalDbConfig(
                dbPath = Path.of(dbPath),
                cursorPath = Path.of(AppConfig.DEFAULT_KAKAO_LOCAL_DB_CURSOR_PATH),
                ownerUserId = UserId(ownerUserId),
                chats = parseChats(chatsJson),
            )
        }

        internal fun parseChats(raw: String): List<KakaoChatSource> {
            val records = JsonSerializer.json.decodeFromString<List<KakaoChatSourceConfig>>(raw)
            require(records.isNotEmpty()) { "${AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON} must not be empty" }
            val chatIds = mutableSetOf<String>()
            return records.map { record ->
                val chatId = record.chatId.trim()
                val sourceName = record.sourceName.trim()
                require(chatId.isNotEmpty()) { "Kakao chatId must not be blank" }
                require(sourceName.isNotEmpty()) { "Kakao sourceName must not be blank: $chatId" }
                require(chatIds.add(chatId)) { "Duplicate Kakao chatId: $chatId" }
                KakaoChatSource(chatId, sourceName, record.access(chatId))
            }
        }
    }
}

/** One allowlisted chat. Its access is fixed, as every record of a source keeps the access it was saved with. */
data class KakaoChatSource(
    val chatId: String,
    val sourceName: String,
    val access: MemoryAccess,
)

@Serializable
private data class KakaoChatSourceConfig(
    val chatId: String,
    val sourceName: String,
    val isPublic: Boolean,
    val allowedUserIds: Set<String> = emptySet(),
) {
    fun access(chatId: String): MemoryAccess = if (isPublic) {
        require(allowedUserIds.isEmpty()) { "PUBLIC Kakao chat must not include allowedUserIds: $chatId" }
        MemoryAccess.PUBLIC
    } else {
        MemoryAccess.restricted(allowedUserIds.map(::UserId))
    }
}
