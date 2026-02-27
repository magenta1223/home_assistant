const { createTestDb } = require('../helpers/db');
const { addSchedule, listSchedules } = require('../../commands/schedule');

jest.mock('../../nlp/claudeClient', () => ({
    parseDate: jest.fn(async (text) => {
        if (text.includes('내일')) return '2026-02-28T00:00';
        if (text.includes('3월')) return '2026-03-01T00:00';
        return null;
    }),
    parseDateRange: jest.fn(async (text) => {
        if (text.includes('이번 주')) return { from: '2026-02-23', to: '2026-03-01' };
        return { from: '2026-02-27', to: '2026-03-28' };
    }),
}));

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('addSchedule', () => {
    test('일정 추가 후 ephemeral 응답 및 DB 저장', async () => {
        const res = await addSchedule(db, 'U1', '내일 치과');
        expect(res.response_type).toBe('ephemeral');
        expect(res.text).toContain('치과');
        expect(res.text).toContain('2026-02-28');

        const row = db.prepare('SELECT * FROM schedules WHERE user_id = ?').get('U1');
        expect(row.event_date).toBe('2026-02-28T00:00');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 붙이면 is_shared=1, in_channel 응답', async () => {
        const res = await addSchedule(db, 'U1', '공유 3월 가족 여행');
        expect(res.response_type).toBe('in_channel');
        const row = db.prepare('SELECT * FROM schedules WHERE user_id = ?').get('U1');
        expect(row.is_shared).toBe(1);
    });

    test('빈 입력 시 안내 메시지 반환', async () => {
        const res = await addSchedule(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
    });

    test('날짜 파싱 실패 시 안내 메시지 반환, DB 저장 안 됨', async () => {
        const { parseDate } = require('../../nlp/claudeClient');
        parseDate.mockResolvedValueOnce(null);
        const res = await addSchedule(db, 'U1', '날짜없는일정');
        expect(res.text).toContain('날짜');
        expect(db.prepare('SELECT * FROM schedules').get()).toBeUndefined();
    });
});

describe('listSchedules', () => {
    beforeEach(async () => {
        await addSchedule(db, 'U1', '내일 치과');
        await addSchedule(db, 'U2', '공유 3월 가족 여행');
    });

    test('U1 일정 조회 시 본인 + 공유 일정 포함', async () => {
        const res = await listSchedules(db, 'U1', '이번 주');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('치과');
        expect(text).toContain('가족 여행');
    });
});
