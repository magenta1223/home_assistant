const { createTestDb } = require('../helpers/db');
const { addMemo, listMemos, searchMemos } = require('../../commands/memo');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('addMemo', () => {
    test('개인 메모 저장 후 ephemeral 응답', () => {
        const res = addMemo(db, 'U1', '치과 예약');
        expect(res.response_type).toBe('ephemeral');
        const row = db.prepare('SELECT * FROM memos WHERE user_id = ?').get('U1');
        expect(row.content).toBe('치과 예약');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 붙이면 is_shared=1, in_channel 응답', () => {
        const res = addMemo(db, 'U1', '공유 이번 주 대청소');
        expect(res.response_type).toBe('in_channel');
        const row = db.prepare('SELECT * FROM memos WHERE user_id = ?').get('U1');
        expect(row.content).toBe('이번 주 대청소');
        expect(row.is_shared).toBe(1);
    });

    test('빈 입력 시 안내 메시지, DB 저장 안 됨', () => {
        const res = addMemo(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
        expect(db.prepare('SELECT * FROM memos').get()).toBeUndefined();
    });
});

describe('listMemos', () => {
    beforeEach(() => {
        addMemo(db, 'U1', '개인 메모');
        addMemo(db, 'U2', '공유 공용 메모');
        addMemo(db, 'U2', 'U2 개인 메모');
    });

    test('U1은 본인 메모 + 공유 메모 볼 수 있음', () => {
        const res = listMemos(db, 'U1', '');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('개인 메모');
        expect(text).toContain('공용 메모');
        expect(text).not.toContain('U2 개인 메모');
    });

    test('"공유" 필터 시 공유 메모만', () => {
        const res = listMemos(db, 'U1', '공유');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('공용 메모');
        expect(text).not.toContain('개인 메모');
    });
});

describe('searchMemos', () => {
    beforeEach(() => {
        addMemo(db, 'U1', '치과 예약 3월');
        addMemo(db, 'U1', '공유 마트 장보기');
    });

    test('검색어로 내 메모 + 공유 메모 검색', () => {
        const res = searchMemos(db, 'U1', '치과');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('치과');
    });

    test('검색 결과 없으면 안내 메시지 포함', () => {
        const res = searchMemos(db, 'U1', '없는키워드');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('없어요');
    });

    test('빈 검색어 시 안내 메시지 반환', () => {
        const res = searchMemos(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
    });
});
