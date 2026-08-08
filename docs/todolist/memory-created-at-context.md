# Memory 생성 일자와 응답 context

- 상태: TODO
- 목표: Weekend MVP

## 문제

DB에는 생성 시각이 있지만 domain memory와 검색·응답 context에는 노출되지 않는다. 같은 부모 아래 과거와 최신 memory가 함께 있을 때 응답기가 시간 순서를 판단할 수 없다.

## 계획

1. `Memory`에 생성 시각을 추가한다.
2. DB insert 시 한 번 계산한 시각을 저장 객체에도 동일하게 사용한다.
3. 검색 결과에 생성 시각을 포함한다.
4. Codex reference에는 사람이 읽을 수 있는 ISO 날짜·시각으로 전달한다.
5. 질문의 현재 시점과 memory 생성 시점을 고려하도록 응답 prompt를 수정한다.
6. 최신 정보라고 단정할 근거가 없으면 memory 날짜를 밝혀 답하게 한다.
7. 생성 시각은 사건 발생 시각과 다르다는 제약을 문서화한다.

## 완료 조건

- 저장된 모든 memory에서 생성 시각을 읽을 수 있다.
- 응답기가 여러 memory의 저장 순서를 비교할 수 있다.
- 기존 DB memory도 migration 후 읽을 수 있다.
