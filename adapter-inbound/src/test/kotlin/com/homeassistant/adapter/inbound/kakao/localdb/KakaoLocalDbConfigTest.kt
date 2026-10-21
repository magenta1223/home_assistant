package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KakaoLocalDbConfigTest {
    @Test
    fun `no chat allowlist disables the sync`() {
        assertNull(KakaoLocalDbConfig.fromEnv { null })
    }

    @Test
    fun `allowlisted chats carry their fixed access`() {
        val config = KakaoLocalDbConfig.fromEnv(
            env(
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_OWNER_USER_ID to "dad",
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON to """
                    [
                      {"chatId": "100", "sourceName": "가족 단톡방", "isPublic": false, "allowedUserIds": ["mom", "dad"]},
                      {"chatId": "200", "sourceName": "집 공지", "isPublic": true}
                    ]
                """,
            ),
        )!!

        assertEquals(UserId("dad"), config.ownerUserId)
        assertEquals(Path.of(AppConfig.DEFAULT_KAKAO_LOCAL_DB_PATH), config.dbPath)
        assertEquals(
            listOf(
                KakaoChatSource("100", "가족 단톡방", MemoryAccess.restricted(listOf(UserId("mom"), UserId("dad")))),
                KakaoChatSource("200", "집 공지", MemoryAccess.PUBLIC),
            ),
            config.chats,
        )
    }

    @Test
    fun `an allowlist without an owner is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KakaoLocalDbConfig.fromEnv(
                env(AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON to """[{"chatId":"1","sourceName":"a","isPublic":true}]"""),
            )
        }
    }

    @Test
    fun `ambiguous chat entries are rejected`() {
        val invalid = listOf(
            """[{"chatId":"1","sourceName":"a","isPublic":true,"allowedUserIds":["mom"]}]""",
            """[{"chatId":"1","sourceName":"a","isPublic":false}]""",
            """[{"chatId":"1","sourceName":"a","isPublic":true},{"chatId":"1","sourceName":"b","isPublic":true}]""",
            """[{"chatId":"1","sourceName":" ","isPublic":true}]""",
            "[]",
        )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { KakaoLocalDbConfig.parseChats(raw) }
        }
    }

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get
}
