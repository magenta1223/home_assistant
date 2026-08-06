package com.homeassistant.adapter.inbound.kakao

import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoExportParserTest {
    @Test
    fun `parser reads exported date sender lines and skips date separators`() {
        val text = """
            홍승민 님과 카카오톡 대화
            저장한 날짜 : 2026년 6월 15일 오전 6:43


            2026년 3월 15일 오후 1:58
            2026년 3월 15일 오후 1:58, 동훈 : 우리은행 1002266102280
            2026년 3월 15일 오후 5:48, 홍승민 : 수자인 부동산에 현 세입자 이사일 & 시간 정해졌는지 확인 (중도금 연락하면서)

            장박사 부동산에 집 나갔는지 확인
            2026년 3월 16일 오전 7:20
            2026년 3월 16일 오전 7:20, 홍승민 : 가는즁
        """.trimIndent()

        val records = KakaoExportParser.parse("home-second-brain-test.txt", text)

        assertEquals(3, records.size)
        assertEquals("kakao", records[0].sourceType)
        assertEquals("home-second-brain-test.txt", records[0].sourceName)
        assertEquals("동훈 | 2026년 3월 15일 오후 1:58 | 우리은행 1002266102280", records[0].content)
        assertEquals(
            "홍승민 | 2026년 3월 15일 오후 5:48 | " +
                "수자인 부동산에 현 세입자 이사일 & 시간 정해졌는지 확인 (중도금 연락하면서)\n\n" +
                "장박사 부동산에 집 나갔는지 확인",
            records[1].content,
        )
        assertEquals("홍승민 | 2026년 3월 16일 오전 7:20 | 가는즁", records[2].content)
    }

    @Test
    fun `parser keeps multiline map payload with the preceding message`() {
        val text = """
            [동훈] [오후 4:49] 따랑해
            [홍승민] [오후 5:38] [네이버지도]
            카인드커피
            경기 수원시 영통구 삼성로168번길 5 삼성중앙빌딩 1층 카인드커피
            https://naver.me/5MvUGczc
            [홍승민] [오후 5:38] 여기루 와용 ㅎㅎ
        """.trimIndent()

        val records = KakaoExportParser.parse("2026-06-07.txt", text)

        assertEquals(3, records.size)
        assertEquals(
            "홍승민 | 오후 5:38 | [네이버지도]\n카인드커피\n" +
                "경기 수원시 영통구 삼성로168번길 5 삼성중앙빌딩 1층 카인드커피\nhttps://naver.me/5MvUGczc",
            records[1].content,
        )
    }
}
