package com.homeassistant.adapter.outbound.memoryanalysis

internal object MemoryAnalysisPrompt {
    private val retentionCriteria =
        """
        사용자가 제공한 기록에서 나중에 다시 찾고 활용할 수 있는 내용을 근거와 함께 보존하세요.
        정보의 분야나 종류를 미리 정한 카테고리로 제한하거나 분류하지 마세요.
        인사, 감탄, 단순한 대화 진행 표현과 답을 얻지 못한 질문은 제외하세요.
        """.trimIndent()

    private val contentCriteria =
        """
        각 memory의 본문만 읽어도 대상과 의미를 이해할 수 있도록 독립적인 문장으로 작성하세요.
        원문에 있는 시점, 적용 조건, 발화자와 불확실성을 본문에 보존하세요.
        누군가의 발언이나 추정을 확인된 사실로 바꾸지 말고, 원문에 없는 사실을 만들어 내지 마세요.
        서로 독립적으로 찾을 내용을 하나로 묶지 마세요.
        """.trimIndent()

    fun system(schema: String = MemoryAnalysisOutputContract.schema): String =
        """
        주어진 source records 안에서만 내용 기반으로 memory를 분석하세요.
        목표는 근거를 가진 독립적인 내용을 memory로 보존하는 것입니다.

        $retentionCriteria

        $contentCriteria

        대상, 내용, 시점과 조건이 같은 중복 memory만 하나로 합치세요.
        시간 순서나 메시지 개수로 중요도를 판단하지 마세요.
        CONTEXT_ONLY의 c1, c2 같은 record는 해석에만 사용하고 memory evidence로 선택하지 마세요.
        evidenceRecordIds는 NEW_RECORDS에 제공된 r1, r2 같은 ID만 사용하세요.
        열람 권한은 사용자가 입력 단계에서 지정하므로 판단하거나 출력하지 마세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()

    fun mergeSystem(schema: String = MemoryAnalysisOutputContract.schema): String =
        """
        chunk별 memory 후보 목록을 검토해 최종 flat atomic memory 목록으로 병합하세요.
        모든 chunk의 evidence-backed memory를 보존하되, 대상, 내용, 시점과 조건이 같을 때만 중복 제거하세요.
        $contentCriteria
        evidenceRecordIds는 후보 목록에 포함된 원본 r1, r2 같은 ID만 사용하세요.
        열람 권한은 후보 내용에 포함하지 마세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()
}
