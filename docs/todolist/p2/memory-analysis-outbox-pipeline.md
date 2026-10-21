# Memory 분석 파이프라인 outbox 전환

- 상태: TODO
- 우선순위: P2
- 선행 작업: [Topic 할당 outbox와 worker](../p1/topic-assignment-outbox.md)

## 문제

지식 주입 HTTP 요청 안에서 첨부 해석(LM)과 Memory 추출(LM)을 동기로 수행한다. LM 지연이 응답 지연이 되고,
backend 장애 시 요청이 `503`으로 실패해 사용자가 다시 보내야 한다.

## 목표

외부 호출 단계를 commit 이후 outbox로 옮겨 Source 저장 → 추출 → Memory 저장 → 색인·할당 → Group의 파이프라인으로 만든다.

## 검토할 것

- 첨부 해석: 지금은 Source 저장 전에 해석한다(`MemoryAnalysisService`). 원본 blob(`source_references`)을 먼저 저장하고 worker가 해석하도록 순서를 바꾼다.
- Memory 추출: `source_records.analysis_status`가 사실상 outbox다. 시도 횟수·오류·backoff 열을 추가한다.
- UI 계약: 추출 결과 건수·해석 불가 오류를 동기 응답 대신 "접수됨" + 처리 상태 조회로 바꾼다. 권한·열람자·중복 검증은 동기로 거절한다.
- 대화는 동기 요청이므로 대상이 아니다.
