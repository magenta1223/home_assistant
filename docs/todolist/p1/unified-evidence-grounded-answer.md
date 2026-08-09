# HTTP·Slack 통합 evidence-grounded 응답

- 상태: TODO
- 우선순위: P1
- 선행 작업: P0 memory 검색 운영 경로 복구

## 문제

Slack은 검색 context를 Codex에 전달해 답변하지만 HTTP answer endpoint는 첫 번째 direct memory의
content를 고정 문구에 붙인다. 같은 사용자와 질문이 진입 경로에 따라 다르게 처리된다. 또한 memory가
evidence ID, certainty, type을 저장해도 실제 Codex reference에는 content 중심 정보만 전달되어 응답기가
근거 원문과 확실성을 비교할 수 없다.

## 목표 구조

```text
HTTP / Slack
    -> 공통 MemoryAnswer input port
    -> visible memory 검색과 bounded tree context 확장
    -> evidence bulk 조회와 reference 구성
    -> 공통 AnswerGenerator output port
    -> 채널별 response mapping
```

raw 검색이 필요하면 answer endpoint와 별도의 명시적인 search 계약으로 둔다.

## 구현 계획

1. answer orchestration을 하나의 application use case로 통합하고 Slack과 HTTP가 동일 input port를 호출하게 한다.
2. Codex 기반 answer generation을 기술 중립적인 `AnswerGenerator` output port 뒤로 이동한다.
3. 공통 prompt builder와 no-match/provider-failure 정책을 application에 둔다.
4. 검색 match에 subject, memory type, certainty, visibility와 저장 시각을 포함한다.
5. source record ID 집합으로 evidence 내용을 한 번에 조회하는 output port를 추가한다.
6. context budget 안에서 memory와 핵심 evidence 문장을 구조화해 렌더링한다.
7. memory와 evidence 모두 untrusted data로 표시하고 그 안의 지시를 따르지 않도록 prompt를 고정한다.
8. certainty가 낮거나 evidence가 충돌할 때 단정하지 않고 부족한 점을 명시하도록 응답 정책을 추가한다.
9. API 응답의 `matches` 또는 별도 `sources` 필드로 실제 사용한 memory/evidence를 추적 가능하게 한다.
10. Slack의 10분 Codex thread가 이전 turn의 근거를 현재 질문의 근거로 오인하지 않도록 매 turn 현재 reference 경계를 명확히 한다.

## 회귀 테스트와 evaluation

- 동일 사용자·질문·memory fixture에서 HTTP와 Slack이 같은 answer context를 사용한다.
- RESTRICTED memory와 그 evidence가 허용되지 않은 사용자 context에 포함되지 않는다.
- direct seed와 허용된 child 확장만 answer generator에 전달된다.
- evidence가 없는 질문은 저장된 memory에 답이 없다고 응답한다.
- 낮은 certainty와 상충 evidence fixture에서 단정형 답변이 나오지 않는지 고정 evaluation으로 확인한다.
- memory 또는 evidence 안의 prompt injection 문장이 답변 지시로 실행되지 않는다.

## 완료 조건

- HTTP와 Slack이 동일한 검색·context·answer generation 흐름을 사용한다.
- 응답기가 memory 내용뿐 아니라 evidence와 certainty를 확인할 수 있다.
- answer와 raw search API 의미가 구분된다.
- 사용자에게 반환된 답변이 어떤 memory/evidence를 사용했는지 추적할 수 있다.
- no-match와 provider failure 동작이 채널별 계약에 맞게 일관된다.

## 제외 범위

- 웹 검색이나 외부 지식 결합
- 자동 citation UI의 고급 표시 형식
- 장기 Slack 대화 memory 생성
