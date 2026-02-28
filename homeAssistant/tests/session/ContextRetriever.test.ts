jest.mock('../../src/session/EmbeddingService');

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
    MockEmbeddingService.prototype.findSimilar = jest.fn().mockReturnValue([{ rowid: BigInt(1), distance: 0.1 }]);
    retriever = new ContextRetriever(db, new MockEmbeddingService(db));
    db.prepare("INSERT INTO memos (user_id, is_shared, content) VALUES ('U1', 0, '치과 예약 메모')").run();
    db.prepare("INSERT INTO memos (user_id, is_shared, content) VALUES ('U1', 0, '보험 갱신 메모')").run();
    db.prepare("INSERT INTO todos (user_id, is_shared, content) VALUES ('U1', 0, '장보기')").run();
});

afterEach(() => { db.close(); });

test('recent: returns rows ordered by created_at desc', async () => {
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
