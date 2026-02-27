import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { AssetCommand } from '../../src/commands/AssetCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new AssetCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/자산', () => {
    test('자산 기록 후 ephemeral 응답', async () => {
        const respond = await app.trigger('/자산', '현금 500000');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('현금'),
        }));
        const row = db.prepare('SELECT * FROM assets WHERE user_id = ?').get('U_TEST') as { category: string; amount: number };
        expect(row.category).toBe('현금');
        expect(row.amount).toBe(500000);
    });

    test('쉼표 포함 금액도 파싱', async () => {
        await app.trigger('/자산', '현금 500,000');
        const row = db.prepare('SELECT * FROM assets WHERE user_id = ?').get('U_TEST') as { amount: number };
        expect(row.amount).toBe(500000);
    });

    test('금액 미입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/자산', '현금');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
    });

    test('숫자 아닌 금액 → 안내 메시지', async () => {
        const respond = await app.trigger('/자산', '현금 많다');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('숫자'),
        }));
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/자산', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });
});

describe('/자산확인', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO assets (user_id, category, amount) VALUES (?, ?, ?)').run('U_TEST', '현금', 300000);
        db.prepare('INSERT INTO assets (user_id, category, amount) VALUES (?, ?, ?)').run('U_TEST', '주식', 1000000);
    });

    test('카테고리별 최신 자산 blocks 반환', async () => {
        const respond = await app.trigger('/자산확인', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('현금');
        expect(text).toContain('주식');
    });

    test('자산 없으면 안내 메시지', async () => {
        db.prepare('DELETE FROM assets').run();
        const respond = await app.trigger('/자산확인', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });
});

describe('/자산내역', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO assets (user_id, category, amount) VALUES (?, ?, ?)').run('U_TEST', '현금', 300000);
        db.prepare('INSERT INTO assets (user_id, category, amount) VALUES (?, ?, ?)').run('U_TEST', '현금', 500000);
    });

    test('카테고리 필터로 내역 조회', async () => {
        const respond = await app.trigger('/자산내역', '현금');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('현금');
    });

    test('카테고리 없으면 전체 내역', async () => {
        const respond = await app.trigger('/자산내역', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        expect(call.blocks).toBeDefined();
    });
});
