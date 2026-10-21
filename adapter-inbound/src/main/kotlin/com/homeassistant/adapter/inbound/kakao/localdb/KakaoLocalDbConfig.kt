package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * The chats to collect from the Kakao export folder. Collection is opt-in: an unlisted chat is never read.
 * The external exporter reads the same allowlist to know which room to export into which `<chatKey>.txt`.
 */
data class KakaoLocalDbConfig(
    val exportDir: Path,
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
            val exportDir = readEnv(AppConfig.ENV_VAR_KAKAO_EXPORT_DIR)?.takeIf(String::isNotBlank)
                ?: AppConfig.DEFAULT_KAKAO_EXPORT_DIR
            return KakaoLocalDbConfig(
                exportDir = Path.of(exportDir),
                cursorPath = Path.of(AppConfig.DEFAULT_KAKAO_LOCAL_DB_CURSOR_PATH),
                ownerUserId = UserId(ownerUserId),
                chats = parseChats(chatsJson),
            )
        }

        internal fun parseChats(raw: String): List<KakaoChatSource> {
            val records = JsonSerializer.json.decodeFromString<List<KakaoChatSourceConfig>>(raw)
            require(records.isNotEmpty()) { "${AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON} must not be empty" }
            val chatKeys = mutableSetOf<String>()
            val roomNames = mutableSetOf<String>()
            return records.map { record ->
                val chatKey = record.chatKey.trim()
                val roomName = record.roomName.trim()
                val sourceName = record.sourceName.trim()
                require(CHAT_KEY.matches(chatKey)) { "Kakao chatKey must match ${CHAT_KEY.pattern}: $chatKey" }
                require(roomName.isNotEmpty()) { "Kakao roomName must not be blank: $chatKey" }
                require(sourceName.isNotEmpty()) { "Kakao sourceName must not be blank: $chatKey" }
                require(chatKeys.add(chatKey)) { "Duplicate Kakao chatKey: $chatKey" }
                require(roomNames.add(roomName)) { "Duplicate Kakao roomName: $roomName" }
                KakaoChatSource(chatKey, roomName, sourceName, record.access(chatKey))
            }
        }

        /** The chat key names the export file, so it stays a portable file name. */
        private val CHAT_KEY = Regex("[A-Za-z0-9_-]+")
    }
}

/**
 * One allowlisted chat: the exporter opens the KakaoTalk room [roomName] and saves it as `<chatKey>.txt`.
 * Its access is fixed, as every record of a source keeps the access it was saved with.
 */
data class KakaoChatSource(
    val chatKey: String,
    val roomName: String,
    val sourceName: String,
    val access: MemoryAccess,
)

@Serializable
private data class KakaoChatSourceConfig(
    val chatKey: String,
    val roomName: String,
    val sourceName: String,
    val isPublic: Boolean,
    val allowedUserIds: Set<String> = emptySet(),
) {
    fun access(chatKey: String): MemoryAccess = if (isPublic) {
        require(allowedUserIds.isEmpty()) { "PUBLIC Kakao chat must not include allowedUserIds: $chatKey" }
        MemoryAccess.PUBLIC
    } else {
        MemoryAccess.restricted(allowedUserIds.map(::UserId))
    }
}
