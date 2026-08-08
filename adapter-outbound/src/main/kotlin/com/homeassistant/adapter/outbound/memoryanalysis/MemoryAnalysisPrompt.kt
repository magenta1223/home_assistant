package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility

internal object MemoryAnalysisPrompt {
    private val retentionCriteria =
        """
        미래의 가족 구성원이나 assistant가 검색, 질문, 계획, 결정 또는 행동에 재사용할 수 있는 정보를 찾으세요.
        정확한 참조 정보, 결정과 합의, 약속과 일정, 현재 상태와 변화, 선호와 제약,
        절차와 규칙, 식별 가능한 사건과 거래, 재사용 가능한 관찰을 포함하세요.
        인사, 감탄, 단순한 대화 진행 표현, 답을 얻지 못한 질문, 확인되지 않은 가정은 제외하세요.
        """.trimIndent()

    fun system(schema: String = MemoryAnalysisOutputContract.schema): String =
        """
        주어진 source records 안에서만 내용 기반으로 memory를 분석하세요.
        목표는 대화를 요약하는 것이 아니라 가족·집 second brain에 바로 저장할
        evidence-backed atomic memory를 찾는 것입니다.

        $retentionCriteria

        각 memory는 하나의 독립된 사실, 결정, 일정, 상태, 선호, 제약, 절차 또는 관찰만 표현해야 합니다.
        서로 다른 사실을 하나의 memory에 묶지 마세요.
        같은 미래 질문에 답하는 중복 memory는 하나로 합치세요.
        시간 순서나 메시지 개수로 중요도를 판단하지 마세요.
        evidenceRecordIds는 입력에 제공된 r1, r2 같은 ID만 사용하세요.
        실제로 말하지 않은 사실을 확정하지 말고 관찰/발화/추론/불확실성을 구분하세요.
        memoryType은 ${MemoryType.entries.joinToString(", ") { it.name }} 중 하나만 사용하세요.
        visibility는 일반적인 가족 공동 정보에만 ${MemoryVisibility.PUBLIC}을 사용하세요.
        건강, 금융, 자격 증명, 민감한 관계, 개인적인 고민에는 ${MemoryVisibility.PRIVATE}을 사용하세요.
        공개 여부가 애매하면 반드시 ${MemoryVisibility.PRIVATE}을 선택하세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()

    fun mergeSystem(schema: String = MemoryAnalysisOutputContract.schema): String =
        """
        chunk별 memory 후보 목록을 검토해 최종 flat atomic memory 목록으로 병합하세요.
        모든 chunk의 evidence-backed memory를 보존하되, 동일한 사실만 중복 제거하세요.
        서로 다른 사실을 요약하거나 합쳐서 하나의 memory로 만들지 마세요.
        evidenceRecordIds는 후보 목록에 포함된 원본 r1, r2 같은 ID만 사용하세요.
        실제로 말하지 않은 사실을 확정하지 말고 관찰/발화/추론/불확실성을 구분하세요.
        memoryType은 ${MemoryType.entries.joinToString(", ") { it.name }} 중 하나만 사용하세요.
        각 후보의 visibility를 보존하세요. 같은 memory 후보의 visibility가 충돌하면
        더 제한적인 ${MemoryVisibility.PRIVATE}을 선택하세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()
}
