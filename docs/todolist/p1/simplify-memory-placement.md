# Memory 배치 구현 단순화

- 상태: TODO
- 목표: Weekend MVP

## 문제

repository는 batch 관계를 한 transaction에서 최종 graph로 검증하고 적용한다. 따라서 batch parent를 먼저 나열하기 위한 `MemoryPlacementResponseOrderer`는 저장 결과에 영향을 주지 않는다. 배치 후 부모를 다시 벡터 인덱싱하지만 embedding text는 변하지 않고 `childrenIds` payload도 검색에서 사용하지 않는다.

## 계획

1. response cycle 검증은 유지한다.
2. `MemoryPlacementResponseOrderer`와 관련 테스트를 제거한다.
3. 검증된 response에서 attach request를 직접 만든다.
4. 배치 후 부모 memory 재인덱싱을 제거한다.
5. `MemoryPlacementService`에서 index writer 의존성을 제거한다.
6. attach response가 실제 소비되지 않는다면 request/response 규칙 안에서 계약을 단순화한다.
7. request validation 위치가 프로젝트의 SRP 원칙과 일치하는지 정리한다.

## 완료 조건

- 한 번의 Codex call과 한 번의 원자적 attach 동작이 유지된다.
- batch 내부 parent 지정과 cycle 차단이 유지된다.
- 의미 없는 정렬과 Qdrant 재호출이 사라진다.
