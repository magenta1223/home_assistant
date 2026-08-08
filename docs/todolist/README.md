# Home Second Brain 작업 목록

이 디렉터리는 구현 전 계획과 구현 후 release note를 함께 관리한다.

## 운영 규칙

1. 구현 전에 작업별 Markdown 문서를 작성한다.
2. 문서에는 문제, 목표, 범위, 구현 순서, 완료 조건을 기록한다.
3. 구현 후 실제 변경 내용과 검증 결과를 추가한다.
4. 완료된 문서는 파일명을 유지한 채 `done/`으로 이동한다.
5. 취소된 작업도 취소 사유를 기록한 뒤 `done/`으로 이동한다.

## 현재 작업

| 문서 | 목적 |
|---|---|
| [memory-search-ranking-and-limit.md](p0/memory-search-ranking-and-limit.md) | 검색된 memory 자체, score 순서와 limit 적용 |
| [retryable-memory-analysis.md](p0/retryable-memory-analysis.md) | Codex 분석 실패 후 같은 import 재시도 |
| [kakao-import-integrity.md](p1/kakao-import-integrity.md) | Kakao dedup, 날짜, 증분 문맥과 대용량 처리 |
| [model-inferred-memory-visibility.md](p0/model-inferred-memory-visibility.md) | 모델이 PUBLIC/PRIVATE을 명시적으로 결정 |
| [simplify-memory-placement.md](p1/simplify-memory-placement.md) | 의미 없는 정렬과 부모 재인덱싱 제거 |
| [reliable-indexing-and-placement-retry.md](p2/reliable-indexing-and-placement-retry.md) | 인덱싱·배치 실패 기록과 재시도 |
| [evidence-grounded-answer.md](p2/evidence-grounded-answer.md) | evidence와 certainty를 실제 답변에 전달 |
| [memory-query-performance.md](p2/memory-query-performance.md) | 전체 조회와 N+1 query 제거 |
| [unify-answer-path.md](p2/unify-answer-path.md) | HTTP와 Slack 답변 동작 통일 |
| [regression-test-baseline.md](p2/regression-test-baseline.md) | 안정화 시점의 최소 회귀 테스트 |

## 완료된 작업

완료 문서는 [done](done/)에서 버전별 release note로 사용한다.

| 문서 | 목적 |
|---|---|
| [memory-created-at-context.md](done/memory-created-at-context.md) | memory 생성 일자를 응답 context에 제공 |
| [tree-aware-memory-context-expansion.md](done/tree-aware-memory-context-expansion.md) | 직접 검색 seed를 유지하며 관련 하위 memory를 제한적으로 context에 확장 |
