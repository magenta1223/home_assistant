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
    fun `allowlisted chats carry their room and fixed access`() {
        val config = KakaoLocalDbConfig.fromEnv(
            env(
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_OWNER_USER_ID to "dad",
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON to """
                    [
                      {"chatKey": "family", "roomName": "우리 가족", "sourceName": "가족 단톡방", "isPublic": false, "allowedUserIds": ["mom", "dad"]},
                      {"chatKey": "house", "roomName": "집 공지방", "sourceName": "집 공지", "isPublic": true}
                    ]
                """,
            ),
        )!!

        assertEquals(UserId("dad"), config.ownerUserId)
        assertEquals(Path.of(AppConfig.DEFAULT_KAKAO_EXPORT_DIR), config.exportDir)
        assertEquals(
            listOf(
                KakaoChatSource("family", "우리 가족", "가족 단톡방", MemoryAccess.restricted(listOf(UserId("mom"), UserId("dad")))),
                KakaoChatSource("house", "집 공지방", "집 공지", MemoryAccess.PUBLIC),
            ),
            config.chats,
        )
    }

    @Test
    fun `the export folder can be moved`() {
        val config = KakaoLocalDbConfig.fromEnv(
            env(
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_OWNER_USER_ID to "dad",
                AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON to """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":true}]""",
                AppConfig.ENV_VAR_KAKAO_EXPORT_DIR to "D:/kakao",
            ),
        )!!

        assertEquals(Path.of("D:/kakao"), config.exportDir)
    }

    @Test
    fun `an allowlist without an owner is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KakaoLocalDbConfig.fromEnv(
                env(AppConfig.ENV_VAR_KAKAO_LOCAL_DB_CHATS_JSON to """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":true}]"""),
            )
        }
    }

    @Test
    fun `ambiguous chat entries are rejected`() {
        val invalid = listOf(
            """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":true,"allowedUserIds":["mom"]}]""",
            """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":false}]""",
            """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":true},{"chatKey":"a","roomName":"b","sourceName":"b","isPublic":true}]""",
            """[{"chatKey":"a","roomName":"a","sourceName":"a","isPublic":true},{"chatKey":"b","roomName":"a","sourceName":"b","isPublic":true}]""",
            """[{"chatKey":"a","roomName":"a","sourceName":" ","isPublic":true}]""",
            """[{"chatKey":"a","roomName":" ","sourceName":"a","isPublic":true}]""",
            """[{"chatKey":"../a","roomName":"a","sourceName":"a","isPublic":true}]""",
            "[]",
        )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { KakaoLocalDbConfig.parseChats(raw) }
        }
    }

    private fun env(vararg values: Pair<String, String>): (String) -> String? = values.toMap()::get
}
