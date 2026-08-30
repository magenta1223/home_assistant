# 명시적인 memory 배치 모델 결정

- 상태: 취소
- 우선순위: P1
- 완료일: 2026-08-29
- 선행 작업: P0 memory read와 placement retry 복구 완료

## 취소 결과

현재 semantic search와 제한적인 tree context 확장이 사용자의 memory-backed 질문에 필요한 검색을
제공하고 있으며, 별도 Topic·Tag·Container lifecycle이 필요한 실제 사용자 문제가 확인되지 않았다.
제품 원칙상 memory-backed retrieval로 해결되는 문제를 별도의 구조화 기능과 관리 대상으로 만들지
않는다.

따라서 Topic 생성·병합 정책, 관계 schema migration, taxonomy 평가를 지금 구현하는 계획은 취소한다.
기존 placement 관계는 내부 검색 보조 정보로 유지한다. 향후 실제 검색 품질, 무결성 또는 성능
계측에서 문제가 확인되면 그 증거와 최소 변경 범위로 새 작업을 작성한다. 전체 조회와
`childrenIds` 관련 성능 가능성은 기존 `memory-query-performance.md`에서 계속 추적한다.

이번 결정에 따른 코드 및 데이터 migration은 없다.

## 문제

현재 분석기는 atomic memory만 생성한다. placement는 새로운 topic/container를 만들지 못하고 기존
memory 또는 같은 batch의 memory를 parent로 선택한다. 그 결과 사실이 다른 사실의 디렉터리 역할을
하며, tree가 커질수록 관계 의미가 불명확해질 수 있다. `childrenIds` JSON은 single-parent 규칙을 DB
schema로 표현하지 못하고 역방향 parent 조회와 무결성 검증도 어렵게 만든다.

## 먼저 결정할 질문

1. 사용자가 원하는 배치는 탐색용 topic taxonomy인가, 검색 확장용 연관 관계인가?
2. 하나의 memory가 여러 topic에 속할 수 있는가?
3. topic은 독립 lifecycle과 이름/설명을 가지는가?
4. 배치 실패가 검색 가능성을 막아야 하는가, 아니면 검색을 보조하는 projection이어야 하는가?

기본 권장안은 **검색 가능한 canonical memory는 flat하게 유지하고, 별도 Topic 노드와
memory-topic 관계를 검색 보조 projection으로 두는 것**이다. 한 memory가 여러 생활 영역에 속할 수
있으므로 topic taxonomy가 필요하지 않다면 복수 tag가 single-parent tree보다 단순하다.

## 대안 비교

| 대안 | 적합한 경우 | 주요 비용 |
|---|---|---|
| 별도 Topic + memory-topic 관계 | 탐색 가능한 지식 분류가 필요함 | topic 생성·병합·이름 변경 정책 필요 |
| 복수 tag | 느슨한 분류와 filtering이면 충분함 | 계층 탐색이 약함 |
| 명시적 container memory + `parent_id` | 반드시 directory tree가 필요함 | atomic fact와 container lifecycle 분리 필요 |

## 구현 계획

1. 대표 Kakao memory 30~50개를 사용해 세 대안의 실제 배치 결과를 작은 evaluation으로 비교한다.
2. 위 결정 질문과 evaluation 결과를 ADR로 기록하고 하나의 모델만 선택한다.
3. 선택된 개념을 domain model과 application port에 명시하고 LLM 응답 schema에도 node 종류를 구분한다.
4. 관계를 JSON 배열이 아닌 정규화된 table로 저장한다.
   - Topic 방식: `topics`, `memory_topics`, 선택적으로 `topic_parent`
   - Tree 방식: memory/container의 nullable `parent_id` 또는 관계 table
5. visibility 규칙을 정의한다. RESTRICTED memory가 PUBLIC topic/container에 연결돼도 허용되지 않은 사용자에게 노출되지 않아야 한다.
6. cycle, orphan, 중복 관계, 삭제/병합 시 동작을 DB와 domain invariant로 고정한다.
7. 기존 `childrenIds`를 새 구조로 옮기는 idempotent migration과 rollback 전 백업 절차를 만든다.
8. 검색 seed 확장이 새 관계를 사용하되 direct semantic result를 제거하거나 순서를 바꾸지 않게 한다.
9. placement prompt가 source record의 지시를 따르지 않도록 입력을 untrusted data로 명시한다.

## 완료 조건

- memory와 분류/container 개념의 차이가 domain model에 드러난다.
- 배치 관계의 cardinality와 single/multi-parent 규칙이 DB schema로 강제된다.
- RESTRICTED memory가 관계 확장 과정에서 허용되지 않은 사용자에게 노출되지 않는다.
- 기존 memory를 손실 없이 migration하고 재실행해도 관계가 중복되지 않는다.
- 대표 fixture에서 사람이 이해할 수 있는 분류 품질을 보이고, direct 검색 recall을 훼손하지 않는다.

## 제외 범위

- 범용 knowledge graph와 임의 edge type
- 자동 ontology 생성
- 사용자용 topic 관리 UI
