import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { GroceryCommand } from '../../src/commands/GroceryCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new GroceryCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/구매', () => {
    test('구매 기록 후 in_channel 응답, 재고 증가', async () => {
        const respond = await app.trigger('/구매', '달걀 10개');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
            text: expect.stringContaining('달걀'),
        }));
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { current_qty: number };
        expect(item.current_qty).toBe(10);
    });

    test('두 번 구매하면 재고 누적', async () => {
        await app.trigger('/구매', '달걀 10개');
        await app.trigger('/구매', '달걀 5개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { current_qty: number };
        expect(item.current_qty).toBe(15);
    });

    test('트랜잭션에 양수 delta 기록', async () => {
        await app.trigger('/구매', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        const tx = db.prepare('SELECT * FROM grocery_transactions WHERE item_id = ?').get(item.id) as { delta: number };
        expect(tx.delta).toBe(10);
    });

    test('소수점 수량 파싱: "우유 1.5L"', async () => {
        await app.trigger('/구매', '우유 1.5L');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('우유') as { current_qty: number };
        expect(item.current_qty).toBe(1.5);
    });

    test('잘못된 형식 → 안내 메시지', async () => {
        const respond = await app.trigger('/구매', '달걀');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('형식'),
        }));
    });
});

describe('/사용', () => {
    beforeEach(async () => {
        await app.trigger('/구매', '달걀 10개');
    });

    test('사용 기록 후 in_channel 응답, 재고 감소', async () => {
        const respond = await app.trigger('/사용', '달걀 3개');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
        }));
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { current_qty: number };
        expect(item.current_qty).toBe(7);
    });

    test('재고보다 많이 사용해도 0 이하로 내려가지 않음', async () => {
        await app.trigger('/사용', '달걀 20개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { current_qty: number };
        expect(item.current_qty).toBe(0);
    });

    test('트랜잭션에 음수 delta 기록', async () => {
        await app.trigger('/사용', '달걀 3개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        const tx = db.prepare('SELECT * FROM grocery_transactions WHERE item_id = ? AND delta < 0').get(item.id) as { delta: number };
        expect(tx.delta).toBe(-3);
    });
});

describe('/재고', () => {
    test('재고 없으면 안내 메시지', async () => {
        const respond = await app.trigger('/재고', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });

    test('재고 현황 blocks 반환', async () => {
        await app.trigger('/구매', '달걀 10개');
        const respond = await app.trigger('/재고', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('달걀');
    });

    test('min_qty 이하인 항목은 부족 표시(⚠️)', async () => {
        await app.trigger('/구매', '달걀 10개');
        db.prepare('UPDATE grocery_items SET current_qty = 0 WHERE name = ?').run('달걀');
        const respond = await app.trigger('/재고', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('⚠️');
    });
});
