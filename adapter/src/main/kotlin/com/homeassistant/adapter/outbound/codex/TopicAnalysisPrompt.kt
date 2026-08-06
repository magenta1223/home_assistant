package com.homeassistant.adapter.outbound.codex

import com.homeassistant.domain.memory.MemoryType

internal object TopicAnalysisPrompt {
    private val retentionCriteria =
        """
        미래의 가족 구성원이나 assistant가 검색, 질문, 계획, 결정 또는 행동에 재사용할 수 있는 다음 정보를 후보로 찾으세요.
        - 정확한 참조 정보: 계좌, 주소, 연락처, 계약번호, 제품 모델, 업체, 예약번호, 물건 위치처럼 값을 다시 찾을 수 있는 정보
        - 결정과 합의: 최종 선택, 하지 않기로 한 것, 역할 분담, 비용 부담처럼 이후 행동에 영향을 주는 정보
        - 약속과 일정: 예약, 기한, 이사, 공사, 방문처럼 미래 행동이나 시간에 영향을 주는 정보
        - 상태와 변화: 가족 구성원, 주거, 계약, 신청, 구매, 건강 등의 현재 상태와 변경 이력
        - 선호와 제약: 반복적인 추천이나 판단에 사용할 취향, 예산, 알레르기, 시간·공간 제약, 금기
        - 절차와 규칙: 다시 수행할 수 있는 방법, 관리 규칙, 체크리스트, 문제 해결 방법, 루틴
        - 식별 가능한 사건과 거래: 대상, 시점, 금액, 결과 등 나중에 다른 사건과 구별할 문맥이 충분한 기록
        - 재사용 가능한 관찰: 반복되거나 수치화되고 적용 상황이 분명하여 향후 계획, 건강, 안전 또는 행동에 영향을 줄 수 있는 관찰

        정보의 중요도를 메시지 수, 반복 횟수, 대화 길이 또는 감정적 강조만으로 판단하지 마세요.
        한 번만 짧게 언급된 정보도 정확한 참조, 결정, 일정 또는 재사용 가능한 사실이면 포함하세요.

        기억 후보로 보존할 정보와 독립 topic으로 만들 정보를 구분하세요. 독립 topic은 다음 조건을 모두 만족해야 합니다.
        - 미래에 그 topic만을 대상으로 질문하거나 조회할 합리적인 가능성이 있습니다.
        - 주체, 대상, 사건 또는 적용 상황을 다른 기억과 구별할 수 있습니다.
        - 대화 직후가 지나도 다시 사용할 사실, 상태, 결정, 일정, 절차 또는 관찰이 있습니다.
        - 더 큰 사건이나 프로젝트 topic의 memory로 포함하는 것보다 독립적으로 둘 가치가 있습니다.

        다음은 독립 topic으로 만들지 마세요.
        - 인사, 감탄, 이모티콘, 단순한 대화 진행 표현과 미래 행동에 도움이 되지 않는 일회성 잡담
        - 답을 아직 얻지 못한 질문이나 확인 요청 하나. 관련 프로젝트의 checklist memory로 병합하세요.
        - 대상이나 시점을 식별할 수 없는 일회성 거래
        - 확인되지 않은 가정, 책임 판단 또는 서로 충돌하는 기억
        - 같은 대상과 미래 조회 목적을 가진 기존 topic의 세부 조건이나 단일 작업 단계
        """.trimIndent()

    fun system(schema: String = TopicAnalysisOutputContract.schema): String =
        """
        주어진 source document 또는 chunk 안에서만 내용 기반으로 주제 분석하세요.
        목표는 대화를 요약하는 것이 아니라 가족·집 second brain에 검토 후보로 저장할 독립적인 기억을 빠짐없이 찾는 것입니다.

        $retentionCriteria

        입력이 200 records보다 크더라도 뒤쪽 기록이 앞쪽의 유효한 기억을 밀어내지 않게 다음 순서로 검토하세요.
        1. 전체 입력을 순서대로 최대 200 records씩 내부 검토 구간으로 나눕니다.
        2. 각 구간에서 위 기준을 통과하는 임시 후보를 빠짐없이 찾습니다.
        3. 모든 구간의 후보를 모은 뒤 같은 미래 질문에 답하는 후보만 통합합니다.
        4. 최종 응답 전에 각 구간의 유효 후보가 최종 topic에 표현되었는지 다시 대조합니다.

        각 topic은 미래의 자연스러운 질문이나 하나의 행동 목적을 지원해야 합니다.
        같은 가족 프로젝트나 대상에 속하고 보통 함께 조회할 정보는 하나로 묶으세요.
        반대로 미래 조회 목적이 분명히 다르면 같은 생활 영역이어도 분리하세요.
        시간 간격으로 나누지 마세요. 같은 주제가 A-B-A 순서로 반복되면 하나로 병합하세요.
        memories 최대 3개 제한만을 맞추기 위해 의미적으로 같은 topic을 여러 개로 쪼개지 마세요.
        같은 topic의 세부사항이 많으면 미래 재사용성이 가장 높은 원자적 memory를 최대 3개 선택하세요.
        evidenceRecordIds는 topic당 최대 5개, memories는 topic당 최대 3개로 제한하세요.
        각 topic은 가족/집 second brain에 승인 후보로 올릴 수 있는 evidence-backed memory를 1개 이상 포함해야 합니다.
        evidenceRecordIds는 사용자 메시지에 제공된 r1, r2 같은 ID만 사용하세요.
        실제로 말하지 않은 사실을 확정하지 말고, 관찰/발화/추론/불확실성을 구분하세요.
        memoryType은 ${MemoryType.entries.joinToString(", ") { it.name }} 중 하나만 사용하세요.
        categories는 housing, moving, travel, food, finance 같은 생활 영역 태그이며 memoryType과 분리하세요.
        최종 응답 전에 REFERENCE, DECISION, APPOINTMENT, STATE, PREFERENCE, CONSTRAINT, TRANSACTION,
        ROUTINE, INSTRUCTION, OBSERVATION 종류별로 독립 topic 기준을 통과한 후보가 누락되지 않았는지 다시 점검하세요.
        기준을 통과한 후보를 입력 앞부분에 있거나 저빈도라는 이유만으로 생략하지 마세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()

    fun mergeSystem(schema: String = TopicAnalysisOutputContract.schema): String =
        """
        chunk별 topic 후보 목록을 검토해 최종 topic 후보로 병합하세요.
        목표는 모든 chunk에서 발견된 독립적이고 재사용 가능한 기억을 최종 결과에 보존하는 것입니다.

        $retentionCriteria

        동일한 미래 질문에 답하고 동일한 사건, 프로젝트 또는 대상을 설명하며 보통 함께 조회할 후보만 병합하세요.
        같은 category라는 이유만으로 합치지 말고, 시간상 떨어져 있어도 같은 주제라면 병합하세요.
        저빈도 topic이나 evidence가 하나뿐이어도 독립 topic 기준을 통과하면 유지하세요.
        memories 최대 3개 제한만을 맞추기 위해 의미적으로 같은 topic을 여러 개로 쪼개지 마세요.
        같은 topic의 세부사항이 많으면 미래 재사용성이 가장 높은 원자적 memory를 최대 3개 선택하세요.
        topic 개수에 상한을 두거나 중요도 순으로 일부 후보를 버리지 마세요.
        evidenceRecordIds는 topic당 최대 5개, memories는 topic당 최대 3개로 제한하세요.
        evidenceRecordIds는 후보 목록에 포함된 원본 r1, r2 같은 source record ID만 사용하세요.
        실제로 말하지 않은 사실을 확정하지 말고 관찰/발화/추론/불확실성을 구분하세요.
        memoryType은 ${MemoryType.entries.joinToString(", ") { it.name }} 중 하나만 사용하세요.
        categories는 housing, moving, travel, food, finance 같은 생활 영역 태그이며 memoryType과 분리하세요.
        최종 응답 전에 모든 입력 후보가 최종 topic에 포함되거나 의미상 완전히 중복되어 병합되었는지 점검하세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()
}
