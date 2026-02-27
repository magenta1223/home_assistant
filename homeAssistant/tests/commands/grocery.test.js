const { createTestDb } = require('../helpers/db');
const { parseItem, addPurchase, addUsage, getInventory } = require('../../commands/grocery');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('parseItem', () => {
    test('정상 파싱: "달걀 10개"', () => {
        expect(parseItem('달걀 10개')).toEqual({ name: '달걀', qty: 10, unit: '개' });
    });

    test('소수점 수량 파싱: "우유 1.5L"', () => {
        expect(parseItem('우유 1.5L')).toEqual({ name: '우유', qty: 1.5, unit: 'L' });
    });

    test('단위 없으면 null 반환', () => {
        expect(parseItem('달걀 10')).toBeNull();
    });

    test('수량 없으면 null 반환', () => {
        expect(parseItem('달걀')).toBeNull();
    });
});

describe('addPurchase', () => {
    test('구매 기록 후 재고 증가', () => {
        addPurchase(db, 'U1', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        expect(item.current_qty).toBe(10);
    });

    test('두 번 구매하면 재고 누적', () => {
        addPurchase(db, 'U1', '달걀 10개');
        addPurchase(db, 'U1', '달걀 5개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        expect(item.current_qty).toBe(15);
    });

    test('트랜잭션에 양수 delta 기록됨', () => {
        addPurchase(db, 'U1', '달걀 10개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        const tx = db.prepare('SELECT * FROM grocery_transactions WHERE item_id = ?').get(item.id);
        expect(tx.delta).toBe(10);
    });

    test('잘못된 형식이면 안내 메시지 반환', () => {
        const res = addPurchase(db, 'U1', '달걀');
        expect(res.text).toContain('형식');
    });
});

describe('addUsage', () => {
    beforeEach(() => { addPurchase(db, 'U1', '달걀 10개'); });

    test('사용 기록 후 재고 감소', () => {
        addUsage(db, 'U1', '달걀 3개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        expect(item.current_qty).toBe(7);
    });

    test('재고보다 많이 사용해도 0 이하로 내려가지 않음', () => {
        addUsage(db, 'U1', '달걀 20개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        expect(item.current_qty).toBe(0);
    });

    test('트랜잭션에 음수 delta 기록됨', () => {
        addUsage(db, 'U1', '달걀 3개');
        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀');
        const tx = db.prepare('SELECT * FROM grocery_transactions WHERE item_id = ? AND delta < 0').get(item.id);
        expect(tx.delta).toBe(-3);
    });
});

describe('getInventory', () => {
    test('재고 없으면 안내 메시지', () => {
        const res = getInventory(db);
        expect(res.text).toContain('없어요');
    });

    test('재고 현황 blocks 반환', () => {
        addPurchase(db, 'U1', '달걀 10개');
        const res = getInventory(db);
        expect(res.blocks).toBeDefined();
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('달걀');
    });

    test('min_qty 이하인 항목은 부족 표시(⚠️)', () => {
        addPurchase(db, 'U1', '달걀 10개');
        // min_qty 기본값 1, 재고 10이므로 정상 — 재고를 0으로 만들기
        db.prepare('UPDATE grocery_items SET current_qty = 0 WHERE name = ?').run('달걀');
        const res = getInventory(db);
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('⚠️');
    });
});
