import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { ScheduleCommand } from '../../src/commands/ScheduleCommand';

jest.mock('../../src/nlp/claudeClient', () => ({
    parseDate: jest.fn(async (text: string) => {
        if (text.includes('내일')) return '2026-02-28T00:00';
        if (text.includes('3월')) return '2026-03-01T00:00';
        return null;
    }),
    parseDateRange: jest.fn(async (text: string) => {
        if (text.includes('이번 주')) return { from: '2026-02-23', to: '2026-03-01' };
        return { from: '2026-02-27', to: '2026-03-28' };
    }),
}));

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new ScheduleCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/일정', () => {
    test('일정 추가 후 ephemeral 응답 및 DB 저장', async () => {
        const respond = await app.trigger('/일정', '내일 치과');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('치과'),
        }));
        const row = db.prepare('SELECT * FROM schedules WHERE user_id = ?').get('U_TEST') as { event_date: string; is_shared: number };
        expect(row.event_date).toBe('2026-02-28T00:00');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 → is_shared=1, in_channel 응답', async () => {
        const respond = await app.trigger('/일정', '공유 3월 가족 여행');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
        }));
        const row = db.prepare('SELECT * FROM schedules WHERE user_id = ?').get('U_TEST') as { is_shared: number };
        expect(row.is_shared).toBe(1);
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/일정', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
    });

    test('날짜 파싱 실패 → 안내 메시지, DB 저장 안 됨', async () => {
        const { parseDate } = jest.requireMock('../../src/nlp/claudeClient') as { parseDate: jest.Mock };
        parseDate.mockResolvedValueOnce(null);
        const respond = await app.trigger('/일정', '날짜없는일정');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('날짜'),
        }));
        expect(db.prepare('SELECT * FROM schedules').get()).toBeUndefined();
    });
});

describe('/일정목록', () => {
    beforeEach(async () => {
        db.prepare('INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)').run('U_TEST', 0, '내일 치과', '2026-02-28T00:00');
        db.prepare('INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)').run('U2', 1, '가족 여행', '2026-03-01T00:00');
    });

    test('본인 + 공유 일정 포함', async () => {
        const respond = await app.trigger('/일정목록', '이번 주');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('치과');
        expect(text).toContain('가족 여행');
    });
});
