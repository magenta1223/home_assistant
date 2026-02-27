const { createTestDb } = require('../helpers/db');
const { recordAsset, getAssets, getAssetHistory } = require('../../commands/asset');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('recordAsset', () => {
    test('자산 기록 후 ephemeral 응답', () => {
        const res = recordAsset(db, 'U1', '현금 500000');
        expect(res.response_type).toBe('ephemeral');
        expect(res.text).toContain('현금');
        expect(res.text).toContain('500,000');

        const row = db.prepare('SELECT * FROM assets WHERE user_id = ?').get('U1');
        expect(row.category).toBe('현금');
        expect(row.amount).toBe(500000);
    });

    test('쉼표 포함 금액도 파싱됨 (500,000)', () => {
        recordAsset(db, 'U1', '현금 500,000');
        const row = db.prepare('SELECT * FROM assets WHERE user_id = ?').get('U1');
        expect(row.amount).toBe(500000);
    });

    test('메모 포함 가능', () => {
        recordAsset(db, 'U1', '저축 1000000 적금');
        const row = db.prepare('SELECT * FROM assets WHERE user_id = ?').get('U1');
        expect(row.note).toBe('적금');
    });

    test('금액 없으면 안내 메시지', () => {
        const res = recordAsset(db, 'U1', '현금');
        expect(res.text).toContain('입력해주세요');
    });

    test('숫자가 아닌 금액이면 안내 메시지', () => {
        const res = recordAsset(db, 'U1', '현금 많음');
        expect(res.text).toContain('숫자');
    });
});

describe('getAssets', () => {
    beforeEach(() => {
        recordAsset(db, 'U1', '현금 300000');
        recordAsset(db, 'U1', '현금 500000'); // 최신값이 500000이어야 함
        recordAsset(db, 'U1', '저축 2000000');
    });

    test('카테고리별 최신 금액 + 합계 표시', () => {
        const res = getAssets(db, 'U1');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('500,000');   // 현금 최신값
        expect(text).toContain('2,000,000'); // 저축
        expect(text).toContain('2,500,000'); // 합계
    });

    test('기록 없으면 안내 메시지', () => {
        const res = getAssets(db, 'UNKNOWN');
        expect(res.text).toContain('없어요');
    });
});

describe('getAssetHistory', () => {
    beforeEach(() => {
        recordAsset(db, 'U1', '현금 300000');
        recordAsset(db, 'U1', '현금 500000');
        recordAsset(db, 'U1', '저축 2000000');
    });

    test('카테고리 지정 시 해당 카테고리 내역만', () => {
        const res = getAssetHistory(db, 'U1', '현금');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('현금');
        expect(text).not.toContain('저축');
    });

    test('카테고리 없으면 전체 내역', () => {
        const res = getAssetHistory(db, 'U1', '');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('현금');
        expect(text).toContain('저축');
    });
});
