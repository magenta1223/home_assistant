# Intent-Context-Command Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** ChatbotCommand가 3단계 파이프라인(intent 분석 → DB context 조회 → command 결정)으로 모든 사용자 발화를 처리하도록 한다.

**Architecture:** Claude 1st call이 intent와 필요한 DB context spec을 JSON으로 반환하면, TypeScript가 spec에 따라 DB에서 데이터를 조회(최근/유사/query)하고, Claude 2nd call이 context를 포함해 최종 command를 결정한다. Vector 검색은 sqlite-vec + @xenova/transformers(로컬 임베딩)로 구현한다.

**Tech Stack:** TypeScript, better-sqlite3, sqlite-vec, @xenova/transformers (Xenova/multilingual-e5-small, 384차원), Jest (ts-jest)

---

## Task 1: Install dependencies

**Files:**
- Modify: `homeAssistant/package.json`

**Step 1: Install packages**

```bash
cd homeAssistant && npm install sqlite-vec @xenova/transformers
```

**Step 2: Verify installation**

```bash
cd homeAssistant && node -e "require('sqlite-vec'); console.log('sqlite-vec OK')"
cd homeAssistant && node -e "require('@xenova/transformers'); console.log('xenova OK')"
```

Expected: 두 줄 모두 `OK` 출력.

**Step 3: Commit**

```bash
git add homeAssistant/package.json homeAssistant/package-lock.json
git commit -m "chore: install sqlite-vec and @xenova/transformers"
```

---

## Task 2: Add types

**Files:**
- Modify: `homeAssistant/src/types/index.ts`

**Step 1: Write the types** — `types/index.ts` 파일 끝에 추가

```typescript
// ── Context pipeline types ──────────────────────────────────────

export type RetrievalType = 'recent' | 'similar' | 'query';

export interface FilterParams {
    keyword?: string;
    dateFrom?: string;
    dateTo?: string;
    category?: string;
    isShared?: boolean;
}

export interface ContextSpec {
    db: 'memos' | 'todos' | 'schedules' | 'home_status'
      | 'item_locations' | 'assets' | 'recipes' | 'grocery_items';
    type: RetrievalType;
    searchText?: string;   // type === 'similar' 일 때
    filter?: FilterParams; // type === 'query' 일 때
}

export interface IntentAnalysis {
    intent: string;
    contexts: ContextSpec[];
}

export interface ContextResult {
    db: string;
    type: RetrievalType;
    rows: Record<string, unknown>[];
}
```

**Step 2: Typecheck**

```bash
cd homeAssistant && npm run typecheck
```

Expected: 오류 없음.

**Step 3: Commit**

```bash
git add homeAssistant/src/types/index.ts
git commit -m "feat: add ContextSpec, IntentAnalysis, ContextResult types"
```

---

## Task 3: Add context config

**Files:**
- Create: `homeAssistant/src/config/context.ts`

**Step 1: Create config file**

```typescript
// homeAssistant/src/config/context.ts

export const CONTEXT_CONFIG = {
    recentLimit: 10,
    similarLimit: 5,
    embeddingModel: 'Xenova/multilingual-e5-small',
    embeddingDimension: 384,
} as const;
```

**Step 2: Typecheck**

```bash
cd homeAssistant && npm run typecheck
```

**Step 3: Commit**

```bash
git add homeAssistant/src/config/context.ts
git commit -m "feat: add context retrieval config"
```

---

## Task 4: DB migration — sqlite-vec virtual tables

**Files:**
- Modify: `homeAssistant/src/db/migrate.ts`
- Modify: `homeAssistant/src/db/init.ts`
- Modify: `homeAssistant/tests/helpers/db.ts`

### 4-1. migrate.ts에 vec0 virtual table 추가

`runMigrations` 함수 내 `db.exec()` 블록 끝에 추가:

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS vec_memos
USING vec0(embedding float[384]);

CREATE VIRTUAL TABLE IF NOT EXISTS vec_todos
USING vec0(embedding float[384]);

CREATE VIRTUAL TABLE IF NOT EXISTS vec_recipes
USING vec0(embedding float[384]);
```

> **Note:** vec0 virtual table은 sqlite-vec 로드 후에만 생성 가능. migrate는 반드시 load 후에 호출해야 한다.

### 4-2. init.ts에 sqlite-vec 로드 추가

`new Database(DB_PATH)` 다음 줄에:

```typescript
import * as sqliteVec from 'sqlite-vec';

// ...기존 코드...
const db = new Database(DB_PATH);
sqliteVec.load(db);  // ← 추가. migrate 전에 위치해야 함
```

### 4-3. tests/helpers/db.ts에도 sqlite-vec 로드

```typescript
import Database from 'better-sqlite3';
import * as sqliteVec from 'sqlite-vec';
import type { Database as DB } from 'better-sqlite3';
import { runMigrations } from '../../src/db/migrate';

export function createTestDb(): DB {
    const db = new Database(':memory:');
    sqliteVec.load(db);           // ← 추가
    db.pragma('foreign_keys = ON');
    runMigrations(db);
    return db;
}
```

**Step: 기존 테스트 실행하여 이상 없음 확인**

```bash
cd homeAssistant && npm test
```

Expected: 65개 테스트 모두 PASS (기존 테스트 회귀 없음).

**Step: Commit**

```bash
git add homeAssistant/src/db/migrate.ts homeAssistant/src/db/init.ts homeAssistant/tests/helpers/db.ts
git commit -m "feat: add sqlite-vec extension and vec0 virtual tables for memos/todos/recipes"
```

---

## Task 5: EmbeddingService

**Files:**
- Create: `homeAssistant/src/session/EmbeddingService.ts`
- Create: `homeAssistant/tests/session/EmbeddingService.test.ts`

### 5-1. 테스트 작성

```typescript
// homeAssistant/tests/session/EmbeddingService.test.ts

jest.mock('@xenova/transformers', () => ({
    pipeline: jest.fn().mockResolvedValue(
        jest.fn().mockResolvedValue({
            data: new Float32Array(384).fill(0.1),
        })
    ),
}));

import { EmbeddingService } from '../../src/session/EmbeddingService';
import { createTestDb } from '../helpers/db';
import type { Database } from 'better-sqlite3';

let db: Database;
let svc: EmbeddingService;

beforeEach(() => {
    db = createTestDb();
    svc = new EmbeddingService(db);
});

afterEach(() => { db.close(); });

test('embed returns Float32Array of correct dimension', async () => {
    const result = await svc.embed('테스트 텍스트');
    expect(result).toHaveLength(384);
});

test('store and findSimilar returns rowIds', async () => {
    const embedding = await svc.embed('달걀');
    svc.store('vec_memos', 1, embedding);
    svc.store('vec_memos', 2, embedding);

    const results = svc.findSimilar('vec_memos', embedding, 5);
    expect(results.length).toBeGreaterThan(0);
    expect(results[0]).toHaveProperty('rowid');
    expect(results[0]).toHaveProperty('distance');
});

test('store overwrites existing embedding for same rowid', async () => {
    const embedding = await svc.embed('달걀');
    svc.store('vec_memos', 1, embedding);
    // upsert: should not throw on duplicate rowid
    expect(() => svc.store('vec_memos', 1, embedding)).not.toThrow();
});
```

**Step: 테스트 실행하여 실패 확인**

```bash
cd homeAssistant && npm test -- --testPathPattern=EmbeddingService
```

Expected: FAIL (모듈 없음).

### 5-2. EmbeddingService 구현

```typescript
// homeAssistant/src/session/EmbeddingService.ts

import type { Database } from 'better-sqlite3';
import { CONTEXT_CONFIG } from '../config/context';

interface SimilarResult {
    rowid: number;
    distance: number;
}

export class EmbeddingService {
    private pipelinePromise: Promise<(text: string, opts: object) => Promise<{ data: Float32Array }>> | null = null;

    constructor(private readonly db: Database) {}

    private getModel() {
        if (!this.pipelinePromise) {
            // Dynamic import to avoid loading the heavy module at startup
            this.pipelinePromise = import('@xenova/transformers').then(m =>
                m.pipeline('feature-extraction', CONTEXT_CONFIG.embeddingModel)
            );
        }
        return this.pipelinePromise;
    }

    async embed(text: string): Promise<Float32Array> {
        const model = await this.getModel();
        const output = await model(text, { pooling: 'mean', normalize: true });
        return output.data instanceof Float32Array
            ? output.data
            : new Float32Array(output.data);
    }

    store(table: string, rowId: number, embedding: Float32Array): void {
        // sqlite-vec upsert: delete then insert
        this.db.prepare(`DELETE FROM ${table} WHERE rowid = ?`).run(rowId);
        this.db.prepare(`INSERT INTO ${table}(rowid, embedding) VALUES (?, ?)`).run(
            rowId,
            Buffer.from(embedding.buffer),
        );
    }

    findSimilar(table: string, queryEmbedding: Float32Array, limit: number): SimilarResult[] {
        return this.db.prepare(`
            SELECT rowid, distance
            FROM ${table}
            WHERE embedding MATCH ?
            ORDER BY distance
            LIMIT ?
        `).all(Buffer.from(queryEmbedding.buffer), limit) as SimilarResult[];
    }
}
```

**Step: 테스트 실행**

```bash
cd homeAssistant && npm test -- --testPathPattern=EmbeddingService
```

Expected: 3개 테스트 PASS.

**Step: Commit**

```bash
git add homeAssistant/src/session/EmbeddingService.ts homeAssistant/tests/session/EmbeddingService.test.ts
git commit -m "feat: add EmbeddingService with sqlite-vec store/search"
```

---

## Task 6: ContextRetriever

**Files:**
- Create: `homeAssistant/src/session/ContextRetriever.ts`
- Create: `homeAssistant/tests/session/ContextRetriever.test.ts`

### 6-1. 테스트 작성

```typescript
// homeAssistant/tests/session/ContextRetriever.test.ts

jest.mock('../session/EmbeddingService');
// EmbeddingService는 Task 5에서 별도 테스트됨. 여기서는 mock 사용.

import { ContextRetriever } from '../../src/session/ContextRetriever';
import { EmbeddingService } from '../../src/session/EmbeddingService';
import { createTestDb } from '../helpers/db';
import type { Database } from 'better-sqlite3';

const MockEmbeddingService = EmbeddingService as jest.MockedClass<typeof EmbeddingService>;

let db: Database;
let retriever: ContextRetriever;

beforeEach(() => {
    db = createTestDb();
    MockEmbeddingService.mockClear();
    MockEmbeddingService.prototype.embed = jest.fn().mockResolvedValue(new Float32Array(384).fill(0.1));
    MockEmbeddingService.prototype.store = jest.fn();
    MockEmbeddingService.prototype.findSimilar = jest.fn().mockReturnValue([{ rowid: 1, distance: 0.1 }]);
    retriever = new ContextRetriever(db, new MockEmbeddingService(db));
    db.prepare("INSERT INTO memos (user_id, is_shared, content) VALUES ('U1', 0, '치과 예약 메모')").run();
    db.prepare("INSERT INTO memos (user_id, is_shared, content) VALUES ('U1', 0, '보험 갱신 메모')").run();
    db.prepare("INSERT INTO todos (user_id, is_shared, content) VALUES ('U1', 0, '장보기')").run();
});

afterEach(() => { db.close(); });

test('recent: returns up to recentLimit rows ordered by created_at desc', async () => {
    const results = await retriever.retrieve(
        [{ db: 'memos', type: 'recent' }],
        'U1',
    );
    expect(results).toHaveLength(1);
    expect(results[0]!.rows.length).toBeGreaterThan(0);
    expect(results[0]!.db).toBe('memos');
});

test('query with keyword: returns matching rows', async () => {
    const results = await retriever.retrieve(
        [{ db: 'memos', type: 'query', filter: { keyword: '치과' } }],
        'U1',
    );
    expect(results[0]!.rows).toHaveLength(1);
    expect((results[0]!.rows[0] as { content: string }).content).toContain('치과');
});

test('query with dateFrom/dateTo: SQL built without error', async () => {
    const results = await retriever.retrieve(
        [{ db: 'schedules', type: 'query', filter: { dateFrom: '2026-01-01', dateTo: '2026-12-31' } }],
        'U1',
    );
    expect(results[0]!.rows).toBeDefined();
});

test('similar: calls EmbeddingService.embed and findSimilar', async () => {
    const results = await retriever.retrieve(
        [{ db: 'memos', type: 'similar', searchText: '치과' }],
        'U1',
    );
    expect(MockEmbeddingService.prototype.embed).toHaveBeenCalledWith('치과');
    expect(MockEmbeddingService.prototype.findSimilar).toHaveBeenCalled();
    expect(results[0]!.db).toBe('memos');
});

test('multiple specs: returns multiple ContextResults', async () => {
    const results = await retriever.retrieve(
        [
            { db: 'memos', type: 'recent' },
            { db: 'todos', type: 'recent' },
        ],
        'U1',
    );
    expect(results).toHaveLength(2);
});
```

**Step: 테스트 실행하여 실패 확인**

```bash
cd homeAssistant && npm test -- --testPathPattern=ContextRetriever
```

Expected: FAIL (모듈 없음).

### 6-2. ContextRetriever 구현

```typescript
// homeAssistant/src/session/ContextRetriever.ts

import type { Database } from 'better-sqlite3';
import type { ContextSpec, ContextResult, FilterParams } from '../types';
import { EmbeddingService } from './EmbeddingService';
import { CONTEXT_CONFIG } from '../config/context';

// 테이블별 메타 정보
const TABLE_META: Record<string, {
    textCol: string;
    hasUserId: boolean;
    dateCol?: string;
    vecTable?: string;
}> = {
    memos:          { textCol: 'content',     hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_memos'   },
    todos:          { textCol: 'content',     hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_todos'   },
    schedules:      { textCol: 'title',       hasUserId: true,  dateCol: 'event_date'                         },
    home_status:    { textCol: 'device_name', hasUserId: false, dateCol: 'updated_at'                         },
    item_locations: { textCol: 'item_name',   hasUserId: false, dateCol: 'updated_at'                         },
    assets:         { textCol: 'category',    hasUserId: true,  dateCol: 'recorded_at'                        },
    recipes:        { textCol: 'name',        hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_recipes' },
    grocery_items:  { textCol: 'name',        hasUserId: false                                                },
};

export class ContextRetriever {
    constructor(
        private readonly db: Database,
        private readonly embedding: EmbeddingService,
    ) {}

    async retrieve(specs: ContextSpec[], userId: string): Promise<ContextResult[]> {
        return Promise.all(specs.map(spec => this.retrieveOne(spec, userId)));
    }

    private async retrieveOne(spec: ContextSpec, userId: string): Promise<ContextResult> {
        switch (spec.type) {
            case 'recent':  return this.retrieveRecent(spec.db, userId);
            case 'query':   return this.retrieveQuery(spec.db, userId, spec.filter ?? {});
            case 'similar': return this.retrieveSimilar(spec.db, userId, spec.searchText ?? '');
        }
    }

    private retrieveRecent(table: string, userId: string): ContextResult {
        const meta = TABLE_META[table];
        if (!meta) return { db: table, type: 'recent', rows: [] };

        const dateCol = meta.dateCol ?? 'rowid';
        const rows = meta.hasUserId
            ? this.db.prepare(
                `SELECT * FROM ${table} WHERE (user_id = ? OR is_shared = 1)
                 ORDER BY ${dateCol} DESC LIMIT ?`
              ).all(userId, CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[]
            : this.db.prepare(
                `SELECT * FROM ${table} ORDER BY ${dateCol} DESC LIMIT ?`
              ).all(CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[];

        return { db: table, type: 'recent', rows };
    }

    private retrieveQuery(table: string, userId: string, filter: FilterParams): ContextResult {
        const meta = TABLE_META[table];
        if (!meta) return { db: table, type: 'query', rows: [] };

        const conditions: string[] = [];
        const params: unknown[] = [];

        if (meta.hasUserId) {
            conditions.push('(user_id = ? OR is_shared = 1)');
            params.push(userId);
        }
        if (filter.keyword) {
            conditions.push(`${meta.textCol} LIKE ?`);
            params.push(`%${filter.keyword}%`);
        }
        if (filter.dateFrom && meta.dateCol) {
            conditions.push(`date(${meta.dateCol}) >= ?`);
            params.push(filter.dateFrom);
        }
        if (filter.dateTo && meta.dateCol) {
            conditions.push(`date(${meta.dateCol}) <= ?`);
            params.push(filter.dateTo);
        }
        if (filter.category) {
            conditions.push('category = ?');
            params.push(filter.category);
        }
        if (filter.isShared !== undefined) {
            conditions.push('is_shared = ?');
            params.push(filter.isShared ? 1 : 0);
        }

        const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
        const rows = this.db.prepare(
            `SELECT * FROM ${table} ${where} LIMIT ?`
        ).all(...params, CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[];

        return { db: table, type: 'query', rows };
    }

    private async retrieveSimilar(table: string, userId: string, searchText: string): Promise<ContextResult> {
        const meta = TABLE_META[table];
        if (!meta || !meta.vecTable) {
            // fallback: vec table 없으면 recent로 대체
            return this.retrieveRecent(table, userId);
        }

        const queryEmbedding = await this.embedding.embed(searchText);
        const similar = this.embedding.findSimilar(meta.vecTable, queryEmbedding, CONTEXT_CONFIG.similarLimit);

        if (!similar.length) return { db: table, type: 'similar', rows: [] };

        const ids = similar.map(s => s.rowid);
        const placeholders = ids.map(() => '?').join(',');
        const rows = this.db.prepare(
            `SELECT * FROM ${table} WHERE id IN (${placeholders})`
        ).all(...ids) as Record<string, unknown>[];

        return { db: table, type: 'similar', rows };
    }
}
```

**Step: 테스트 실행**

```bash
cd homeAssistant && npm test -- --testPathPattern=ContextRetriever
```

Expected: 5개 테스트 PASS.

**Step: Commit**

```bash
git add homeAssistant/src/session/ContextRetriever.ts homeAssistant/tests/session/ContextRetriever.test.ts
git commit -m "feat: add ContextRetriever with recent/query/similar retrieval types"
```

---

## Task 7: analyzeIntent() in claudeClient.ts

**Files:**
- Modify: `homeAssistant/src/nlp/claudeClient.ts`
- Create: `homeAssistant/tests/nlp/analyzeIntent.test.ts`

### 7-1. 테스트 작성

```typescript
// homeAssistant/tests/nlp/analyzeIntent.test.ts

jest.mock('@anthropic-ai/sdk', () => ({
    default: jest.fn().mockImplementation(() => ({
        messages: {
            create: jest.fn(),
        },
    })),
}));

import Anthropic from '@anthropic-ai/sdk';
import { analyzeIntent } from '../../src/nlp/claudeClient';

const mockCreate = (Anthropic as jest.Mock).mock.results[0]?.value.messages.create as jest.Mock;

beforeEach(() => { mockCreate.mockReset(); });

test('returns IntentAnalysis with intent and contexts', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({
                intent: 'memo_search',
                contexts: [{ db: 'memos', type: 'similar', searchText: '치과' }],
            }),
        }],
    });

    const result = await analyzeIntent([], '치과 예약 메모 찾아줘');
    expect(result.intent).toBe('memo_search');
    expect(result.contexts).toHaveLength(1);
    expect(result.contexts[0]!.db).toBe('memos');
    expect(result.contexts[0]!.type).toBe('similar');
});

test('returns empty contexts when no DB needed', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({ intent: 'greeting', contexts: [] }),
        }],
    });

    const result = await analyzeIntent([], '안녕');
    expect(result.contexts).toHaveLength(0);
});

test('returns empty contexts on JSON parse error', async () => {
    mockCreate.mockResolvedValue({
        content: [{ type: 'text', text: 'invalid json' }],
    });

    const result = await analyzeIntent([], '아무말');
    expect(result.intent).toBe('unknown');
    expect(result.contexts).toHaveLength(0);
});
```

**Step: 테스트 실행하여 실패 확인**

```bash
cd homeAssistant && npm test -- --testPathPattern=analyzeIntent
```

### 7-2. analyzeIntent() 구현

`claudeClient.ts`에 다음을 추가한다. 기존 import 및 client 선언 재사용.

**추가할 시스템 프롬프트 상수:**

```typescript
const INTENT_SYSTEM = `당신은 한국 가정용 Slack 봇의 의도 분석기입니다.
사용자 발화를 분석하여 필요한 DB context를 JSON으로 반환하세요.

사용 가능한 DB: memos, todos, schedules, home_status, item_locations, assets, recipes, grocery_items

조회 타입:
- recent: 최근 데이터가 필요할 때
- similar: 특정 내용과 유사한 데이터 검색 (searchText 필드 필수)
- query: 날짜/카테고리/키워드 등 조건 기반 조회 (filter 필드 필수)
  filter 가능 필드: keyword, dateFrom, dateTo, category, isShared

반환 형식 (JSON only):
{ "intent": "...", "contexts": [{ "db": "...", "type": "...", ...옵션 }] }

context가 불필요하면: { "intent": "...", "contexts": [] }
항상 JSON만 반환하세요.`;
```

**추가할 함수:**

```typescript
export async function analyzeIntent(
    history: ConversationMessage[],
    userText: string,
): Promise<IntentAnalysis> {
    const messages: Array<{ role: 'user' | 'assistant'; content: string }> = [
        ...history.map(m => ({ role: m.role, content: m.content })),
        { role: 'user', content: userText },
    ];

    try {
        const response = await client.messages.create({
            model: 'claude-haiku-4-5-20251001',
            max_tokens: 256,
            temperature: 0,
            system: INTENT_SYSTEM,
            messages,
        });

        const block = response.content[0];
        if (!block || block.type !== 'text') return { intent: 'unknown', contexts: [] };

        const parsed = JSON.parse(block.text.trim()) as IntentAnalysis;
        return { intent: parsed.intent ?? 'unknown', contexts: parsed.contexts ?? [] };
    } catch {
        return { intent: 'unknown', contexts: [] };
    }
}
```

**import 추가** (파일 상단):

```typescript
import type { ConversationMessage, ChatResponse, IntentAnalysis } from '../types';
```

**Step: 테스트 실행**

```bash
cd homeAssistant && npm test -- --testPathPattern=analyzeIntent
```

Expected: 3개 PASS.

**Step: Typecheck**

```bash
cd homeAssistant && npm run typecheck
```

**Step: Commit**

```bash
git add homeAssistant/src/nlp/claudeClient.ts homeAssistant/tests/nlp/analyzeIntent.test.ts
git commit -m "feat: add analyzeIntent() to claudeClient"
```

---

## Task 8: chatSession() — context 파라미터 추가

**Files:**
- Modify: `homeAssistant/src/nlp/claudeClient.ts`

### 8-1. chatSession 시그니처 변경

기존:
```typescript
export async function chatSession(
    history: ConversationMessage[],
    userMessage: string,
): Promise<ChatResponse>
```

변경 후:
```typescript
export async function chatSession(
    history: ConversationMessage[],
    userMessage: string,
    context?: ContextResult[],
): Promise<ChatResponse>
```

**import 추가:**
```typescript
import type { ConversationMessage, ChatResponse, IntentAnalysis, ContextResult } from '../types';
```

### 8-2. context를 messages에 삽입

`chatSession` 함수 내 messages 배열 생성 부분 수정:

```typescript
const contextBlock = context && context.length > 0
    ? formatContext(context)
    : '';

const messages: Array<{ role: 'user' | 'assistant'; content: string }> = [
    ...history.map(m => ({ role: m.role, content: m.content })),
    {
        role: 'user',
        content: contextBlock
            ? `[context]\n${contextBlock}\n[/context]\n\n${userMessage}`
            : userMessage,
    },
];
```

### 8-3. formatContext 헬퍼 추가

```typescript
function formatContext(results: ContextResult[]): string {
    return results.map(r => {
        if (!r.rows.length) return '';
        const label = `${r.db} (${r.type})`;
        const lines = r.rows.slice(0, 10).map(row => {
            const values = Object.entries(row)
                .filter(([k]) => !['id', 'user_id', 'is_shared'].includes(k))
                .map(([k, v]) => `${k}: ${String(v)}`)
                .join(', ');
            return `- ${values}`;
        }).join('\n');
        return `${label}:\n${lines}`;
    }).filter(Boolean).join('\n\n');
}
```

**Step: 기존 chatbot 테스트 실행 — context=undefined이면 기존 동작 유지**

```bash
cd homeAssistant && npm test -- --testPathPattern=chatbot
```

Expected: 기존 테스트 모두 PASS (context 파라미터는 optional).

**Step: Typecheck**

```bash
cd homeAssistant && npm run typecheck
```

**Step: Commit**

```bash
git add homeAssistant/src/nlp/claudeClient.ts
git commit -m "feat: add optional context parameter to chatSession()"
```

---

## Task 9: ChatbotCommand — 파이프라인 통합

**Files:**
- Modify: `homeAssistant/src/commands/ChatbotCommand.ts`
- Modify: `homeAssistant/tests/commands/chatbot.test.ts`

### 9-1. 테스트 mock 업데이트

기존 mock 블록을 다음으로 교체:

```typescript
jest.mock('../../src/nlp/claudeClient', () => ({
    chatSession: jest.fn(),
    analyzeIntent: jest.fn(),
    parseDate: jest.fn(),
    parseDateRange: jest.fn(),
}));
```

각 `describe` 블록의 `beforeEach`에 추가:

```typescript
import { analyzeIntent } from '../../src/nlp/claudeClient';
const mockAnalyzeIntent = analyzeIntent as jest.Mock;

// beforeEach 내부:
mockAnalyzeIntent.mockResolvedValue({ intent: 'unknown', contexts: [] });
```

**Step: 테스트 실행하여 현재 상태 확인**

```bash
cd homeAssistant && npm test -- --testPathPattern=chatbot
```

### 9-2. ChatbotCommand 파이프라인 수정

`ChatbotCommand` 생성자에 `ContextRetriever` 추가:

```typescript
import { ContextRetriever } from '../session/ContextRetriever';
import { EmbeddingService } from '../session/EmbeddingService';
import { analyzeIntent } from '../nlp/claudeClient';

export class ChatbotCommand extends BaseCommand {
    private readonly sessions: SessionManager;
    private readonly contextRetriever: ContextRetriever;

    constructor(db: Database, sessionManager?: SessionManager, contextRetriever?: ContextRetriever) {
        super(db);
        this.sessions = sessionManager ?? new SessionManager();
        this.contextRetriever = contextRetriever ?? new ContextRetriever(db, new EmbeddingService(db));
    }
```

`register()` 내부 Claude 호출 부분 교체:

```typescript
// 기존:
const history = this.sessions.getMessages(userId);
const response = await chatSession(history, userText);

// 변경 후:
const history = this.sessions.getMessages(userId);
const intentAnalysis = await analyzeIntent(history, userText);
const contextResults = await this.contextRetriever.retrieve(intentAnalysis.contexts, userId);
const response = await chatSession(history, userText, contextResults);
```

### 9-3. 테스트 업데이트 — 파이프라인 검증 테스트 추가

기존 `describe('DM 메시지 처리')` 블록 안에 추가:

```typescript
test('analyzeIntent가 호출되고 반환된 contexts로 retriever가 실행됨', async () => {
    mockAnalyzeIntent.mockResolvedValueOnce({
        intent: 'memo_search',
        contexts: [{ db: 'memos', type: 'recent' }],
    });
    mockChatSession.mockResolvedValueOnce({ type: 'unknown', text: '결과 없음' });

    await app.triggerMessage('최근 메모 보여줘', 'U1', 'im');

    expect(mockAnalyzeIntent).toHaveBeenCalledWith(expect.any(Array), '최근 메모 보여줘');
    expect(mockChatSession).toHaveBeenCalled();
});
```

**Step: 전체 테스트 실행**

```bash
cd homeAssistant && npm test
```

Expected: 기존 65개 + 신규 테스트 PASS.

**Step: Commit**

```bash
git add homeAssistant/src/commands/ChatbotCommand.ts homeAssistant/tests/commands/chatbot.test.ts
git commit -m "feat: wire intent-context-command pipeline in ChatbotCommand"
```

---

## Task 10: app.ts — 개별 Command 등록 제거

**Files:**
- Modify: `homeAssistant/src/app.ts`

기존 개별 command 등록을 제거하고 ChatbotCommand만 남긴다:

```typescript
import { App } from "@slack/bolt";
import "dotenv/config";
import db from "./db/init";
import { ChatbotCommand } from "./commands/ChatbotCommand";

const app = new App({
    token: process.env["SLACK_BOT_TOKEN"]!,
    signingSecret: process.env["SLACK_SIGNING_SECRET"]!,
    appToken: process.env["SLACK_APP_TOKEN"]!,
    socketMode: true,
});

new ChatbotCommand(db).register(app);

void (async () => {
    await app.start();
    console.log("⚡️ 홈 어시스턴트 봇이 소켓 모드에서 실행 중입니다!");
})();
```

**Step: Typecheck**

```bash
cd homeAssistant && npm run typecheck
```

**Step: 전체 테스트 실행**

```bash
cd homeAssistant && npm test
```

Expected: 모든 테스트 PASS.

**Step: Commit**

```bash
git add homeAssistant/src/app.ts
git commit -m "refactor: route all commands through ChatbotCommand only"
```

---

## Task 11: Embedding 자동 저장 hook

**Note:** row INSERT 시 자동으로 임베딩을 저장해야 similar 검색이 의미 있다.

**Files:**
- Modify: `homeAssistant/src/commands/ChatbotCommand.ts`

`executeCommand` 내 임베딩 대상 테이블(memos, todos, recipes)에 row INSERT 후 embedding 생성/저장 로직 추가.

각 해당 case 블록에서 `this.db.prepare('INSERT INTO ...').run(...)` 이후:

```typescript
// /메모 case 예시:
const inserted = this.db.prepare('SELECT last_insert_rowid() as id').get() as { id: number };
void this.contextRetriever.storeEmbedding('memos', 'vec_memos', inserted.id, content);
```

`ContextRetriever`에 public `storeEmbedding` 메서드 추가:

```typescript
async storeEmbedding(table: string, vecTable: string, rowId: number, text: string): Promise<void> {
    const embedding = await this.embedding.embed(text);
    this.embedding.store(vecTable, rowId, embedding);
}
```

임베딩 저장은 `void` (fire-and-forget) — 응답을 지연시키지 않는다.

**적용 대상 commands:** `/메모`, `/할일`, `/레시피저장`

**Step: Typecheck + 전체 테스트**

```bash
cd homeAssistant && npm run typecheck && npm test
```

**Step: Commit**

```bash
git add homeAssistant/src/commands/ChatbotCommand.ts homeAssistant/src/session/ContextRetriever.ts
git commit -m "feat: auto-store embeddings on memo/todo/recipe insert"
```
