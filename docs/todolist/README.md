# Home Second Brain 작업 목록

이 디렉터리는 구현 전 계획과 구현 후 release note를 함께 관리한다.

## 운영 규칙

1. 구현 전에 작업별 Markdown 문서를 작성한다.
2. 문서에는 문제, 목표, 범위, 구현 순서, 완료 조건을 기록한다.
3. 구현 후 실제 변경 내용과 검증 결과를 추가한다.
4. 완료된 문서는 파일명을 유지한 채 `done/`으로 이동한다.
5. 취소된 작업도 취소 사유를 기록한 뒤 `done/`으로 이동한다.
6. 사용자 학습 과제로 표시된 작업은 활성 구현 우선순위와 분리하고, 사용자의 명시적인 요청 없이
   대신 구현하지 않는다.

## 운영 리스크와 기반 작업

`p0/`, `p1/`, `p2/`는 새 기능의 우선순위가 아니라 현재 기능을 막거나 신뢰성·정확성·프라이버시에 영향을 주는 문제의 우선순위다.

- **P0**: 데이터 손실, 권한 누출, 서비스 불능처럼 즉시 사용을 막는 문제
- **P1**: 현재 데이터 모델이나 핵심 흐름의 정확성을 훼손해 다음 기능 전에 해결해야 하는 문제
- **P2**: 규모 증가나 안정화 단계에서 해결할 성능·회귀 방지 문제

### P0

| 문서 | 목적 |
|---|---|
| [codex-integration-module-extraction.md](p0/codex-integration-module-extraction.md) | 사용자 학습 과제: Codex 저수준 통신을 독립 integration 모듈로 분리하고 outbound에는 기능별 port 구현만 유지 |
| [onnx-embedding-runtime-migration.md](p0/onnx-embedding-runtime-migration.md) | Ollama 자식 서버를 JVM 내부 ONNX Runtime 임베딩으로 교체하고 기존 vector를 안전하게 전체 재색인 |
| [deploy-runtime-shutdown-reliability.md](p0/deploy-runtime-shutdown-reliability.md) | 로컬 구현·회귀 테스트 완료; 새 경로의 원격 배포·재기동 검증 대기 |

### P1

| 문서 | 목적 |
|---|---|
| [slack-integration-module-extraction.md](p1/slack-integration-module-extraction.md) | Slack SDK·Socket Mode·Web API integration과 기능별 application input adapter를 분리 |
| [semantic-index-integration-module-extraction.md](p1/semantic-index-integration-module-extraction.md) | ONNX·Qdrant 기술 integration을 분리하고 outbound에는 semantic-memory port mapping만 유지 |
| [runtime-distribution-module-extraction.md](p1/runtime-distribution-module-extraction.md) | 여러 managed runtime이 공유하는 검증·설치 lifecycle을 독립 기반 모듈로 분리 |

### P2

| 문서 | 목적 |
|---|---|
| [core-technology-boundary-hardening.md](p2/core-technology-boundary-hardening.md) | Domain/Application에서 serialization·logging 구현 결합을 제거하고 외부 전달 경계를 명시적인 port로 강화 |
| [memory-query-performance.md](p2/memory-query-performance.md) | 사용자가 계측부터 직접 공부하며 개선할 Memory query 성능 학습 과제 |

## 제품 기능 계획

`feature/`는 사용자가 접하는 기능과 필요한 전달 채널 확장을 위한 계획이다. 리스크 작업과
독립적으로 우선순위를 매기되, 해당 기능이 P0/P1 리스크를 유발하면 먼저 리스크 작업을 추가한다.

- **Feature P0**: 현재 가정에서 바로 사용할 다음 핵심 기능
- **Feature P1**: P0 기능과 Memory를 종합해 능동적인 운영 경험을 완성하는 기능
- **Feature P2**: 외부 제품화 결정 후 필요한 고객·과금·규모 확장

### Feature P0

| 문서 | 목적 |
|---|---|
| [minimal-family-task-service.md](feature/p0/minimal-family-task-service.md) | 담당자와 완료 여부만 관리하는 최소 가족 Task 서비스 |
| [family-notification-service.md](feature/p0/family-notification-service.md) | Memory 알림 후보 발견, 사용자 승인과 확정 알림의 durable Slack 전달을 분리 |

### Feature P1

| 문서 | 목적 |
|---|---|
| [periodic-household-review.md](feature/p1/periodic-household-review.md) | Memory·Task·알림 이력을 정기적으로 종합해 필요한 내용을 먼저 브리핑 |

### Feature P2

| 문서 | 목적 |
|---|---|
| [api-intelligence-and-billing-model.md](feature/p2/api-intelligence-and-billing-model.md) | 외부 제품화 시 공식 모델 API 지능 계약과 BYOK·관리형 과금 모델 결정 |
| [multi-family-group-expansion.md](feature/p2/multi-family-group-expansion.md) | 약 1,000개 가족 그룹을 위한 로컬 우선·중앙 조율·비동기 작업 운영 구조 결정 |

## 완료된 작업

완료·취소 문서는 [done](done/)에서 지속적으로 찾아볼 수 있는 단일 이력과 release note로 사용한다.
완료 시 원래 계획 문서를 이곳으로 이동하고, 완료일·실제 변경·검증·남은 제약(취소 시 사유)을 기록한다.

| 문서 | 결과 |
|---|---|
| [atomic-memory-analysis-persistence.md](done/atomic-memory-analysis-persistence.md) | 분석 batch 원자 저장, 안정적 idempotency key, durable indexing outbox와 전체 reindex 복구 |
| [explicit-knowledge-audience.md](done/explicit-knowledge-audience.md) | 명시적 PUBLIC/열람자 ACL, 로컬 지식 주입 페이지, Slack 쓰기 경로 제거 |
| [managed-embedding-server.md](done/managed-embedding-server.md) | Windows standalone Ollama 설치·모델 준비·managed server lifecycle 구현 |
| [memory-read-transaction-boundary.md](done/memory-read-transaction-boundary.md) | repository 소유 transaction으로 검색·답변·Slack 문맥·배치 읽기 경로 복구 |
| [memory-search-ranking-and-limit.md](done/memory-search-ranking-and-limit.md) | 검색된 memory 자체, score 순서와 limit 적용 |
| [retryable-memory-analysis.md](done/retryable-memory-analysis.md) | Codex 분석 실패 후 같은 import 재시도 |
| [model-inferred-memory-visibility.md](done/model-inferred-memory-visibility.md) | 과거 모델 추론 방식 기록; 명시적 source audience로 대체됨 |
| [kakao-import-integrity.md](done/kakao-import-integrity.md) | 파일명 독립 dedup, 날짜 보존, 검증된 증분 문맥, 제한된 대용량 분석 |
| [memory-created-at-context.md](done/memory-created-at-context.md) | memory 생성 일자를 응답 context에 제공 |
| [simplify-memory-placement.md](done/simplify-memory-placement.md) | 의미 없는 정렬·부모 재인덱싱·attach 응답 제거 |
| [tree-aware-memory-context-expansion.md](done/tree-aware-memory-context-expansion.md) | 직접 검색 seed를 유지하며 관련 하위 memory를 제한적으로 context에 확장 |
| [explicit-application-ports.md](done/explicit-application-ports.md) | application의 input/output port와 use case 구현 경계 명시화 |
| [usecase-specific-application-exceptions.md](done/usecase-specific-application-exceptions.md) | application 예외 생성과 노출을 use case별 input 계약으로 제한 |
| [unified-evidence-grounded-answer.md](done/unified-evidence-grounded-answer.md) | HTTP 답변 경로 폐기 결정에 따라 통합 계획 취소; Slack은 공통 memory conversation 유스케이스를 중개 |
| [slack-memory-answer-application-boundary.md](done/slack-memory-answer-application-boundary.md) | 사용자 등록·최초 질문 재개·memory 답변 routing을 application으로 이동하고 Slack을 변환·렌더링 adapter로 제한 |
| [regression-test-baseline.md](done/regression-test-baseline.md) | 데이터 손실·권한 누출·검색 결과 소실을 막는 최소 회귀 테스트 기준선 확립 |
| [slack-operational-channel-architecture.md](done/slack-operational-channel-architecture.md) | 범용 운영 채널 계획은 취소하고 작은 slash-command registry 경계만 도입 |
| [slack-knowledge-injection.md](done/slack-knowledge-injection.md) | `/knowledge` modal을 기존 `MemoryAnalysis` 흐름에 연결 |
| [operational-feature-plugin-expansion.md](done/operational-feature-plugin-expansion.md) | manifest·명령 registry까지만 도입하고 범용 plugin 일반화는 보류 |
| [explicit-memory-placement-model.md](done/explicit-memory-placement-model.md) | 실제 필요가 확인되지 않은 Topic·Tag·Container 재설계를 제품 원칙에 따라 취소 |
| [qdrant-windows-app-control-reliability.md](done/qdrant-windows-app-control-reliability.md) | 초기화와 production 원격 기동의 실사용 확인으로 운영 P0 종료; 오류 4551 원인은 잔여 위험으로 기록 |
