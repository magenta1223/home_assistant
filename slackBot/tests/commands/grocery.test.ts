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
    test('구매 기록 후 in_channel 응답', async () => {
        const respond = await app.trigger('/구매', '달걀 10개');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
            text: expect.stringContaining('달걀'),
        }));
    });

    test('grocery_purchases 테이블에 행 생성', async () => {
        await app.trigger('/구매', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        const purchase = db.prepare('SELECT * FROM grocery_purchases WHERE item_id = ?').get(item.id) as { qty: number };
        expect(purchase.qty).toBe(10);
    });

    test('두 번 구매하면 purchases 2행 생성', async () => {
        await app.trigger('/구매', '달걀 10개');
        await app.trigger('/구매', '달걀 5개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        const purchases = db.prepare('SELECT * FROM grocery_purchases WHERE item_id = ?').all(item.id) as unknown[];
        expect(purchases).toHaveLength(2);
    });

    test('소수점 수량 파싱: "우유 1.5L"', async () => {
        await app.trigger('/구매', '우유 1.5L');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('우유') as { id: number };
        const purchase = db.prepare('SELECT * FROM grocery_purchases WHERE item_id = ?').get(item.id) as { qty: number };
        expect(purchase.qty).toBe(1.5);
    });

    test('잘못된 형식 → 안내 메시지', async () => {
        const respond = await app.trigger('/구매', '달걀');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('형식'),
        }));
    });
});

describe('/재고', () => {
    test('재고 없으면 안내 메시지', async () => {
        const respond = await app.trigger('/재고', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });

    test('구매 1회 → 데이터 부족 표시', async () => {
        await app.trigger('/구매', '달걀 10개');
        const respond = await app.trigger('/재고', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('데이터 부족');
    });

    test('구매 2회 이상 → 예측 정보 표시 (데이터 부족 없음)', async () => {
        await app.trigger('/구매', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        // 첫 구매를 14일 전으로 조작하여 간격 생성
        db.prepare("UPDATE grocery_purchases SET purchased_at = datetime('now', '-14 days', 'localtime') WHERE item_id = ?").run(item.id);
        await app.trigger('/구매', '달걀 10개');
        const respond = await app.trigger('/재고', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('달걀');
        expect(text).not.toContain('데이터 부족');
    });

    test('days_remaining <= 0 → ⚠️ 표시', async () => {
        await app.trigger('/구매', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        // 첫 구매 14일 전, 두 번째 구매 7일 전 → 주기 7일, 마지막 구매 7일 전 → daysRemaining ≈ 0
        db.prepare("UPDATE grocery_purchases SET purchased_at = datetime('now', '-14 days', 'localtime') WHERE item_id = ?").run(item.id);
        db.prepare("INSERT INTO grocery_purchases (item_id, qty, purchased_at) VALUES (?, 10, datetime('now', '-7 days', 'localtime'))").run(item.id);
        const respond = await app.trigger('/재고', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('⚠️');
    });
});
