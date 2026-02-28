# Intent-Context-Command Pipeline Design

**Date**: 2026-03-01
**Scope**: `homeAssistant` bot — ChatbotCommand 리팩토링

## Overview

모든 사용자 발화를 ChatbotCommand 하나에서 처리하되, 3단계 파이프라인으로 응답 품질을 높인다.

1. **Intent 분석** — Claude가 발화 의도와 필요한 DB context를 결정
2. **Context 조회** — TypeScript가 ContextSpec에 따라 DB에서 데이터를 가져옴
3. **Command 결정** — Claude가 context를 포함한 정보로 최종 command를 결정

---

## Pipeline Flow

```
User message
     ↓
[claudeClient] analyzeIntent(history, userText)
     → IntentAnalysis { intent, contexts: ContextSpec[] }
     ↓
[ContextRetriever] ContextSpec[] 순회 → DB 조회
     → ContextResult[] (각 테이블의 rows)
     ↓
[claudeClient] chatSession(history, userText, contextResults)
     → ChatResponse { type, command, params }
     ↓
[ChatbotCommand] executeCommand(command, params, userId)
```

---

## Data Structures

### ContextSpec (Claude 1st call 반환)

```typescript
type RetrievalType = 'recent' | 'similar' | 'query';

interface ContextSpec {
    db: 'memos' | 'todos' | 'schedules' | 'home_status'
      | 'item_locations' | 'assets' | 'recipes' | 'grocery_items';
    type: RetrievalType;
    searchText?: string;   // type === 'similar' 일 때 사용
    filter?: {             // type === 'query' 일 때 사용
        keyword?: string;
        dateFrom?: string;
        dateTo?: string;
        category?: string;
        isShared?: boolean;
    };
}

interface IntentAnalysis {
    intent: string;
    contexts: ContextSpec[];
}
```

### ContextResult (DB 조회 결과)

```typescript
interface ContextResult {
    db: string;
    type: RetrievalType;
    rows: Record<string, unknown>[];
}
```

---

## Components

### 1. `claudeClient.ts` — `analyzeIntent()`

**System Prompt:**
```
당신은 한국 가정용 Slack 봇의 의도 분석기입니다.
사용자 발화를 분석하여 필요한 DB context를 JSON으로 반환하세요.

사용 가능한 DB: memos, todos, schedules, home_status,
                item_locations, assets, recipes, grocery_items

조회 타입:
- recent: 최근 데이터가 필요할 때
- similar: 특정 내용과 유사한 데이터 검색 (searchText 필수)
- query: 날짜/카테고리/키워드 등 조건 기반 조회 (filter 필수)

반환 형식 (JSON only):
{ "intent": "...", "contexts": [{ "db": "...", "type": "...", ... }] }

context가 불필요하면: { "intent": "...", "contexts": [] }
```

**Signature:**
```typescript
export async function analyzeIntent(
    history: ConversationMessage[],
    userText: string,
): Promise<IntentAnalysis>
```

### 2. `session/ContextRetriever.ts` — 신규 클래스

```typescript
export class ContextRetriever {
    constructor(private readonly db: Database) {}

    async retrieve(specs: ContextSpec[], userId: string): Promise<ContextResult[]>

    private retrieveRecent(db: string, userId: string): ContextResult
    private async retrieveSimilar(db: string, userId: string, searchText: string): Promise<ContextResult>
    private retrieveQuery(db: string, userId: string, filter: FilterParams): ContextResult
}
```

| type | 동작 |
|---|---|
| `recent` | `ORDER BY created_at DESC LIMIT config.recentLimit` |
| `similar` | searchText → 로컬 임베딩(@xenova/transformers) → sqlite-vec cosine search |
| `query` | filter params → parameterized SQL 조립 |

### 3. `config/context.ts` — 신규

```typescript
export const CONTEXT_CONFIG = {
    recentLimit: 10,
    similarLimit: 5,
    embeddingModel: 'Xenova/multilingual-e5-small',
} as const;
```

### 4. `claudeClient.ts` — `chatSession()` 수정

- 기존 시스템 프롬프트 유지
- `ContextResult[]`를 messages의 앞부분에 삽입:

```
[context]
memos (recent):
- 2026-02-01: 치과 예약 3월 5일 오후 2시
- 2026-01-15: 보험 갱신 필요
[/context]
```

**Signature:**
```typescript
export async function chatSession(
    history: ConversationMessage[],
    userText: string,
    context?: ContextResult[],
): Promise<ChatResponse>
```

### 5. `db/migrate.ts` — 임베딩 테이블 추가

```sql
CREATE TABLE IF NOT EXISTS embeddings (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name TEXT    NOT NULL,
    row_id     INTEGER NOT NULL,
    embedding  BLOB    NOT NULL,
    UNIQUE(table_name, row_id)
);
```

sqlite-vec 로드: `db.loadExtension('vec0')` (init.ts에 추가)

### 6. `ChatbotCommand.ts` — `register()` 수정

```typescript
// 기존: chatSession(history, userText)
// 변경 후:
const intentAnalysis = await analyzeIntent(history, userText);
const contextResults = await this.contextRetriever.retrieve(intentAnalysis.contexts, userId);
const response = await chatSession(history, userText, contextResults);
```

---

## Vector Embedding Strategy

- **모델**: `Xenova/multilingual-e5-small` — 한국어 지원, 경량
- **저장**: `embeddings` 테이블 (sqlite-vec BLOB)
- **생성 시점**: row INSERT/UPDATE 시 자동 생성
- **검색**: cosine similarity, `LIMIT config.similarLimit`

임베딩 대상 테이블: `memos`, `todos`, `recipes` (텍스트 heavy)
나머지 테이블(`home_status`, `item_locations` 등)은 `recent` / `query` 타입만 사용.

---

## Files Changed

| 파일 | 변경 내용 |
|---|---|
| `src/claudeClient.ts` | `analyzeIntent()` 추가, `chatSession()` context 파라미터 추가 |
| `src/commands/ChatbotCommand.ts` | 파이프라인 수정 (analyzeIntent → contextRetriever → chatSession) |
| `src/session/ContextRetriever.ts` | 신규 |
| `src/config/context.ts` | 신규 |
| `src/db/migrate.ts` | embeddings 테이블 추가 |
| `src/db/init.ts` | sqlite-vec 익스텐션 로드 추가 |
| `src/types/index.ts` | ContextSpec, IntentAnalysis, ContextResult 타입 추가 |

---

## Constraints & Notes

- `app.ts`에서 기존 개별 Command 클래스들은 제거 (ChatbotCommand만 남김)
- sqlite-vec는 `better-sqlite3-multiple-ciphers` 또는 native extension 방식으로 로드
- `@xenova/transformers`는 첫 실행 시 모델 다운로드 (캐시됨)
- 임베딩이 없는 row에 대한 similar 검색은 graceful fallback으로 recent로 대체
