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
