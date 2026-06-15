package com.homeassistant.domain.kakao

import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoMessageParserTest {
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

        val messages = KakaoMessageParser.parse(KakaoSourceFileName("2026-06-07.txt"), KakaoExportText(text))

        assertEquals(3, messages.size)
        assertEquals(KakaoSenderName("홍승민"), messages[1].sender)
        assertEquals(2, messages[1].lineStart.value)
        assertEquals(5, messages[1].lineEnd.value)
        assertEquals(
            "[네이버지도]\n카인드커피\n경기 수원시 영통구 삼성로168번길 5 삼성중앙빌딩 1층 카인드커피\nhttps://naver.me/5MvUGczc",
            messages[1].text.value,
        )
    }
}
