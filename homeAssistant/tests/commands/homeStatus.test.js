const { createTestDb } = require('../helpers/db');
const { setStatus, getStatus } = require('../../commands/homeStatus');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('setStatus', () => {
    test('기기 상태 저장 후 in_channel 응답', () => {
        const res = setStatus(db, 'U1', '에어컨 켜');
        expect(res.response_type).toBe('in_channel');
        expect(res.text).toContain('에어컨');
        expect(res.text).toContain('켜');

        const row = db.prepare('SELECT * FROM home_status WHERE device_name = ?').get('에어컨');
        expect(row.status).toBe('켜');
        expect(row.set_by).toBe('U1');
    });

    test('같은 기기 상태를 다시 설정하면 upsert (row 1개 유지)', () => {
        setStatus(db, 'U1', '에어컨 켜');
        setStatus(db, 'U2', '에어컨 꺼');
        const rows = db.prepare('SELECT * FROM home_status WHERE device_name = ?').all('에어컨');
        expect(rows).toHaveLength(1);
        expect(rows[0].status).toBe('꺼');
    });

    test('기기명만 입력 시 안내 메시지 반환', () => {
        const res = setStatus(db, 'U1', '에어컨');
        expect(res.text).toContain('입력해주세요');
    });

    test('빈 입력 시 안내 메시지 반환', () => {
        const res = setStatus(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
    });
});

describe('getStatus', () => {
    beforeEach(() => {
        setStatus(db, 'U1', '에어컨 켜');
        setStatus(db, 'U1', '보일러 꺼');
    });

    test('특정 기기 조회', () => {
        const res = getStatus(db, '에어컨');
        expect(res.text).toContain('에어컨');
        expect(res.text).toContain('켜');
    });

    test('없는 기기 조회 시 안내 메시지', () => {
        const res = getStatus(db, '세탁기');
        expect(res.text).toContain('없어요');
    });

    test('기기명 없이 조회 시 전체 목록 반환 (blocks)', () => {
        const res = getStatus(db, '');
        expect(res.blocks).toBeDefined();
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('에어컨');
        expect(text).toContain('보일러');
    });
});
