# Memory 배치 구현 단순화

- 상태: DONE
- 목표: Weekend MVP
- 완료일: 2026-08-08

## 문제

repository는 batch 관계를 한 transaction에서 최종 graph로 검증하고 적용한다. 따라서 batch parent를 먼저 나열하기 위한 `MemoryPlacementResponseOrderer`는 저장 결과에 영향을 주지 않았다. 배치 후 부모를 다시 벡터 인덱싱했지만 embedding text는 변하지 않고 `childrenIds` payload도 검색에서 사용하지 않았다.

## 실제 변경 내용

1. response의 완전성, 선택 가능한 parent, self-parent, batch cycle 검증은 유지했다.
2. `MemoryPlacementResponseOrderer`를 제거하고 검증된 response에서 attach request를 직접 만든다.
3. `MemoryTreeStore.attachChildren`의 사용되지 않는 response를 제거했다.
4. 배치 후 부모 memory 재인덱싱과 `MemoryPlacementService`의 index writer 의존성을 제거했다.
5. placement 요청의 중복 memory id 검증을 요청 모델의 생성 시점 invariant로 옮겼다.
6. repository가 child-first assignment도 최종 graph 기준으로 적용하고, 기존 graph와 합쳐지는 cycle에서는 전체 batch를 rollback함을 회귀 테스트로 고정했다.

## 사용자에게 보이는 변화

Memory 배치 결과는 같지만, 한 번의 Codex 호출과 한 번의 원자적 attach만 수행한다. 배치 후 내용이 바뀌지 않은 부모를 Qdrant에 다시 쓰지 않는다.

## 검증 결과

- `./gradlew build --rerun-tasks` 통과
- `:application:test` 통과
- `:adapter-outbound:test` 통과
- child-first batch parent 관계 적용 확인
- batch response cycle 사전 차단 확인
- 기존 graph를 포함한 cycle 차단과 transaction rollback 확인
- extractor 1회, attach 1회 호출 확인

## 남은 제약

- placement 실패의 영속 기록과 재시도는 `reliable-indexing-and-placement-retry` 작업 범위다.
- vector payload에 과거 `childrenIds`가 남을 수 있지만 현재 검색에서는 소비하지 않는다. tree-aware 검색은 canonical repository 관계를 기준으로 별도 context 확장 단계에서 구현한다.
