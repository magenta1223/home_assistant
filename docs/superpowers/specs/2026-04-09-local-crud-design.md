# Local CRUD Features Design

**Date:** 2026-04-09  
**Status:** Approved  
**Scope:** 메모, Todo, 자산, 식료품 — 4개 로컬 CRUD 기능

---

## Context

홈 서버 챗봇에 일상 관리 기능을 추가한다. 사용자가 자연어로 말하면 LLM이 적절한 tool을 선택해 실행하는 방식이다. 현재 tool calling 프레임워크는 구현 완료되어 있고, domain 모듈이 완전히 비어 있어 여기에 모든 구현이 들어간다.

접근법: **공유 taxonomy 먼저, 수직 슬라이스로 확장** — taxonomy → 메모 → Todo → 자산 → 식료품 순서로 구현.

---

## Architecture

### Module Structure

```
domain/
  src/main/kotlin/com/homeassistant/domain/
    db/
      DatabaseFactory.kt          ← SQLite 연결 + Exposed 스키마 초기화
      tables/
        TaxonomyTable.kt
        MemoTable.kt
        TodoTable.kt
        AssetTable.kt
        GroceryTable.kt
    taxonomy/
      TaxonomyRepository.kt
      TaxonomyTools.kt
    memo/
      MemoRepository.kt
      MemoTools.kt
    todo/
      TodoRepository.kt
      TodoTools.kt
    asset/
      AssetRepository.kt
      AssetTools.kt
    grocery/
      GroceryRepository.kt
      GroceryTools.kt
    DomainToolRegistry.kt         ← 모든 tool + executor 집합
```

### Data Flow

```
ChatRequest
  → ChatPipeline
    → AiClient (LLM이 tool 선택)
      → DomainToolRegistry.executor (tool 실행)
        → Repository (SQLite)
      → AiClient (결과 기반 최종 응답 생성)
  → ChatResponse
```

### App Wiring (Application.kt)

```kotlin
DatabaseFactory.init()
val registry = DomainToolRegistry()
val aiClient = AiClientFactory.create(tools = registry.tools())
ChatPipeline(aiClient, toolExecutor = registry.executor())
```

기존 `IToolExecutor` 인터페이스와 `ChatPipeline`의 `TOOL_CALL` 흐름을 그대로 활용한다.

---

## DB Schema

### Taxonomy (공유 분류 체계)

```sql
taxonomy_nodes
  id          INTEGER PRIMARY KEY
  parent_id   INTEGER REFERENCES taxonomy_nodes(id) NULLABLE  -- 트리 구조
  name        TEXT NOT NULL
  node_type   TEXT NOT NULL   -- "CATEGORY" | "TAG"
  created_at  INTEGER NOT NULL  -- epoch ms
```

메모와 Todo가 같은 taxonomy 트리를 공유한다. LLM이 동적으로 노드를 생성한다.

### Memo

```sql
memos
  id          INTEGER PRIMARY KEY
  title       TEXT NOT NULL
  content     TEXT NOT NULL
  created_at  INTEGER NOT NULL
  updated_at  INTEGER NOT NULL

memo_taxonomy
  memo_id     INTEGER REFERENCES memos(id)
  node_id     INTEGER REFERENCES taxonomy_nodes(id)
  PRIMARY KEY (memo_id, node_id)
```

### Todo

```sql
todos
  id           INTEGER PRIMARY KEY
  title        TEXT NOT NULL
  status       TEXT NOT NULL    -- "PENDING" | "DONE"
  created_at   INTEGER NOT NULL
  completed_at INTEGER NULLABLE

subtasks
  id           INTEGER PRIMARY KEY
  todo_id      INTEGER REFERENCES todos(id)
  title        TEXT NOT NULL
  status       TEXT NOT NULL    -- "PENDING" | "DONE"
  order_index  INTEGER NOT NULL

todo_taxonomy
  todo_id     INTEGER REFERENCES todos(id)
  node_id     INTEGER REFERENCES taxonomy_nodes(id)
  PRIMARY KEY (todo_id, node_id)
```

### Asset

```sql
assets
  id             INTEGER PRIMARY KEY
  name           TEXT NOT NULL
  asset_type     TEXT NOT NULL   -- "FINANCIAL" | "PHYSICAL"
  purchase_price REAL NULLABLE
  current_value  REAL NULLABLE
  currency       TEXT NOT NULL   -- "KRW" | "USD" 등
  notes          TEXT NULLABLE
  created_at     INTEGER NOT NULL
  updated_at     INTEGER NOT NULL

asset_value_history
  id          INTEGER PRIMARY KEY
  asset_id    INTEGER REFERENCES assets(id)
  value       REAL NOT NULL
  recorded_at INTEGER NOT NULL
```

금융 자산 외부 API 연동(주식 시세, 코인 가격)은 별도 페이즈에서 구현한다.

### Grocery

```sql
grocery_items
  id   INTEGER PRIMARY KEY
  name TEXT NOT NULL UNIQUE

grocery_purchases
  id              INTEGER PRIMARY KEY
  grocery_item_id INTEGER REFERENCES grocery_items(id)
  quantity        REAL NOT NULL
  purchased_at    INTEGER NOT NULL
```

평균 구매 주기는 저장하지 않고 구매 이력에서 계산한다.

---

## Tools (18개)

### Taxonomy (3)

| Tool | 파라미터 | 설명 |
|------|---------|------|
| `taxonomy_create` | name, node_type, parent_id? | 노드 생성 |
| `taxonomy_list` | parent_id? | 트리 조회 (없으면 루트부터) |
| `taxonomy_search` | query | 이름으로 검색 |

### Memo (5)

| Tool | 파라미터 | 설명 |
|------|---------|------|
| `memo_create` | title, content, node_ids[] | 메모 생성 |
| `memo_search` | query, node_ids?[] | 전문 검색 + taxonomy 필터 |
| `memo_list` | node_id? | taxonomy 필터로 목록 조회 |
| `memo_update` | id, title?, content?, node_ids?[] | 수정 |
| `memo_delete` | id | 삭제 |

### Todo (5)

| Tool | 파라미터 | 설명 |
|------|---------|------|
| `todo_create` | title, subtasks?[], node_ids?[] | Todo 생성 |
| `todo_add_subtask` | todo_id, title | subtask 추가 |
| `todo_complete` | todo_id, subtask_id? | 완료 처리 |
| `todo_list` | status?, node_id? | 목록 (경과 시간 포함) |
| `todo_get` | id | subtask 포함 상세 조회 |

### Asset (4)

| Tool | 파라미터 | 설명 |
|------|---------|------|
| `asset_add` | name, asset_type, purchase_price?, current_value?, currency, notes? | 자산 추가 |
| `asset_update_value` | id, value | 현재 가치 갱신 (history 기록) |
| `asset_list` | asset_type? | 목록 조회 |
| `asset_summary` | — | 전체 합계 (type별, currency별) |

### Grocery (3)

| Tool | 파라미터 | 설명 |
|------|---------|------|
| `grocery_record_purchase` | item_name, quantity, purchased_at? | 구매 기록 |
| `grocery_list` | — | 항목별 마지막 구매일 + 평균 주기 |
| `grocery_due` | — | 구매 주기 도래 항목 목록 (마지막 구매일 + 평균 주기 ≤ 오늘) |

---

## Error Handling

Tool executor는 예외를 던지지 않는다. DB 오류나 잘못된 인자는 `ToolResult`에 에러 메시지로 담아 반환하고, LLM이 사용자에게 자연어로 설명한다.

```kotlin
// executor 계층에서 전부 잡음
ToolResult("ERROR: memo not found with id=42")
ToolResult("ERROR: parent taxonomy node 99 does not exist")
```

---

## Testing

- **Repository 단위 테스트**: `:memory:` SQLite로 각 repository CRUD 검증
- **Tool executor 통합 테스트**: `DomainToolRegistry`를 실제 `ToolCallSpec`으로 호출 → `ToolResult` 검증
- **E2E**: `USE_DUMMY_PIPELINE=false`로 서버 실행 후 수동 테스트

```bash
./gradlew :domain:test
./gradlew :app:run
# "삼성전자 주식 100주 자산에 추가해줘"
# "오늘 우유 2개 샀어"
# "이번 주 할 일 목록 보여줘"
# "회의 내용 메모해줘"
```

---

## Implementation Order

1. `DatabaseFactory` + 5개 테이블 (Exposed schema)
2. `TaxonomyRepository` + `TaxonomyTools`
3. `MemoRepository` + `MemoTools`
4. `TodoRepository` + `TodoTools`
5. `AssetRepository` + `AssetTools`
6. `GroceryRepository` + `GroceryTools`
7. `DomainToolRegistry` — 모든 tool/executor 집합
8. `Application.kt` wiring
