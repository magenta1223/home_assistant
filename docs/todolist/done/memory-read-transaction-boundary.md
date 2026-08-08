# Memory read transaction 경계 복구

- 상태: DONE
- 우선순위: P0
- 완료일: 2026-08-08
- 목표: memory 검색과 배치 운영 경로 복구

## 문제

`MemoryRepository.getMemories()`는 Exposed query를 실행하면서 transaction을 열지 않았다.
repository 단위 테스트는 호출자가 `transaction(database)`로 감싸기 때문에 통과했지만,
실제 `MemorySearcher`와 `MemoryPlacementService`는 transaction 없이 호출했다. Exposed 0.57에서
이 경로는 `No transaction in context`로 실패하므로 질문 검색과 신규 memory 배치가 동작하지 않았다.

## 적용 원칙

- persistence transaction은 outbound persistence adapter가 소유한다.
- application use case와 테스트가 Exposed transaction의 존재를 알지 않는다.
- correctness 수정과 query 성능 최적화를 섞지 않는다. 전체 조회와 N+1 개선은 별도 P2 작업으로 유지한다.

## 실제 변경 내용

1. `MemoryRepository.getMemories(userId)` 전체를 repository 내부 `transaction(db)`에서 실행한다.
2. memory row 변환과 evidence 조회를 같은 transaction 안에서 완료하고 완성된 `List<Memory>`만 반환한다.
3. 기존 repository 테스트에서 호출자 측 `transaction(database)` wrapper를 제거해 transaction 소유권을
   persistence adapter 계약으로 고정했다.
4. `RepositoryFactory`의 실제 repository를 `MemorySearcher`, `MemoryGroundedChatbot`,
   `HouseholdContextProvider`, `MemoryPlacementService`에 연결하는 통합 테스트를 추가했다.
5. 통합 테스트에서 PUBLIC과 본인 PRIVATE memory 검색, 타인 PRIVATE 제외, HTTP 답변용 조회,
   Slack context 조회, tree attach까지 caller transaction 없이 검증한다.

## 검증 결과

- 수정 전 외부 transaction wrapper를 제거한 repository 테스트 5건에서
  `No transaction in context` 계열 실패를 재현했다.
- `./gradlew :adapter-outbound:test --rerun-tasks` 통과
- `./gradlew test --rerun-tasks` 통과
- 전체 63개 테스트 성공, 실패·오류·스킵 0개
- `git diff --check` whitespace 오류 없음

## 완료 조건 확인

- 모든 `MemoryReader.getMemories()` 호출자가 persistence 기술을 모른 채 사용할 수 있다.
- 실제 repository를 사용하는 검색과 배치 테스트가 transaction wrapper 없이 통과한다.
- HTTP 질문과 Slack context 조회가 저장된 memory를 정상적으로 읽는다.
- memory 읽기 경로에서 `No transaction in context`가 재발하면 회귀 테스트가 실패한다.

## 제외 범위

- visibility SQL filtering
- evidence bulk query와 N+1 제거
- memory tree 저장 구조 변경
