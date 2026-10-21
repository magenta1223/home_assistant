package com.homeassistant.adapter.inbound.kakao.localdb

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoExportFolderStoreTest {
    private val exportDir: Path = Files.createTempDirectory("kakao-exports")
    private val store = KakaoExportFolderStore(exportDir)

    @Test
    fun `a chat without an export file has no messages`() {
        assertEquals(emptyList(), store.messagesFrom("family", KakaoSyncCursor.START, 200))
    }

    @Test
    fun `exported messages are read in file order with multiline text`() {
        export(
            "family",
            """
            우리 가족 님과 카카오톡 대화
            저장한 날짜 : 2025-01-03 10:00:00

            --------------- 2025년 1월 2일 목요일 ---------------
            [민수] [오전 9:03] 아침 약은 먹었어?
            [영희] [오전 9:04] 응
            두 알 먹었어
            """.trimIndent(),
        )

        val messages = store.messagesFrom("family", KakaoSyncCursor.START, 200)

        assertEquals(
            listOf(
                KakaoLocalMessage("민수", "아침 약은 먹었어?", at(2, 9, 3)),
                KakaoLocalMessage("영희", "응\n두 알 먹었어", at(2, 9, 4)),
            ),
            messages,
        )
    }

    @Test
    fun `messages before the cursor minute are left out and the cursor minute is included`() {
        export(
            "family",
            """
            --------------- 2025년 1월 2일 목요일 ---------------
            [민수] [오전 9:03] a
            [민수] [오전 9:04] b
            [민수] [오전 9:05] c
            """.trimIndent(),
        )

        assertEquals(listOf("b", "c"), store.messagesFrom("family", at(2, 9, 4), 200).map { it.text })
    }

    @Test
    fun `only messages that are nothing but an attachment placeholder are left out`() {
        export(
            "family",
            """
            --------------- 2025년 1월 2일 목요일 ---------------
            [민수] [오전 9:03] 사진
            [민수] [오전 9:03] 이모티콘
            [민수] [오전 9:03] 동영상
            [민수] [오전 9:03] 파일: 관리비.pdf
            [민수] [오전 9:04] 사진 잘 나왔다
            [민수] [오전 9:04] 파일 보냈어
            """.trimIndent(),
        )

        assertEquals(
            listOf("사진 잘 나왔다", "파일 보냈어"),
            store.messagesFrom("family", KakaoSyncCursor.START, 200).map { it.text },
        )
    }

    @Test
    fun `a batch keeps the whole last minute`() {
        export(
            "family",
            """
            --------------- 2025년 1월 2일 목요일 ---------------
            [민수] [오전 9:03] a
            [민수] [오전 9:04] b
            [민수] [오전 9:04] c
            [민수] [오전 9:05] d
            """.trimIndent(),
        )

        assertEquals(listOf("a", "b", "c"), store.messagesFrom("family", KakaoSyncCursor.START, 2).map { it.text })
    }

    @Test
    fun `messages without a date are skipped`() {
        export("family", "[민수] [오전 9:03] 날짜 줄이 없다")

        assertEquals(emptyList(), store.messagesFrom("family", KakaoSyncCursor.START, 200))
    }

    private fun export(chatKey: String, text: String) {
        Files.writeString(exportDir.resolve("$chatKey.txt"), "﻿" + text)
    }

    private fun at(day: Int, hour: Int, minute: Int): Instant =
        LocalDateTime.of(2025, 1, day, hour, minute).atZone(KakaoLocalDbSync.KAKAO_ZONE).toInstant()
}
