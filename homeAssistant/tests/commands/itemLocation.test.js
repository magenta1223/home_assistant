const { createTestDb } = require('../helpers/db');
const { setLocation, getLocation } = require('../../commands/itemLocation');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('setLocation', () => {
    test('물건 위치 저장 후 in_channel 응답', () => {
        const res = setLocation(db, 'U1', '리모컨 소파 옆');
        expect(res.response_type).toBe('in_channel');
        expect(res.text).toContain('리모컨');
        expect(res.text).toContain('소파 옆');

        const row = db.prepare('SELECT * FROM item_locations WHERE item_name = ?').get('리모컨');
        expect(row.location).toBe('소파 옆');
    });

    test('같은 물건 위치 다시 저장하면 upsert (row 1개 유지)', () => {
        setLocation(db, 'U1', '리모컨 소파 옆');
        setLocation(db, 'U1', '리모컨 침대 위');
        const rows = db.prepare('SELECT * FROM item_locations WHERE item_name = ?').all('리모컨');
        expect(rows).toHaveLength(1);
        expect(rows[0].location).toBe('침대 위');
    });

    test('위치 없이 물건명만 입력 시 안내 메시지', () => {
        const res = setLocation(db, 'U1', '리모컨');
        expect(res.text).toContain('입력해주세요');
    });

    test('빈 입력 시 안내 메시지', () => {
        const res = setLocation(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
    });
});

describe('getLocation', () => {
    beforeEach(() => { setLocation(db, 'U1', '리모컨 소파 옆'); });

    test('저장된 위치 조회', () => {
        const res = getLocation(db, '리모컨');
        expect(res.text).toContain('리모컨');
        expect(res.text).toContain('소파 옆');
    });

    test('없는 물건 조회 시 안내 메시지', () => {
        const res = getLocation(db, '충전기');
        expect(res.text).toContain('없어요');
    });

    test('물건명 없이 조회 시 안내 메시지', () => {
        const res = getLocation(db, '');
        expect(res.text).toContain('입력해주세요');
    });
});
