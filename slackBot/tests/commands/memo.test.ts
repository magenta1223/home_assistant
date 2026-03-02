import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { MemoCommand } from '../../src/commands/MemoCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new MemoCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/메모', () => {
    test('개인 메모 저장 후 ephemeral 응답', async () => {
        const respond = await app.trigger('/메모', '치과 예약 내일 3시');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
        const row = db.prepare('SELECT * FROM memos WHERE user_id = ?').get('U_TEST') as { content: string; is_shared: number };
        expect(row.content).toBe('치과 예약 내일 3시');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 → is_shared=1, in_channel 응답', async () => {
        const respond = await app.trigger('/메모', '공유 정수기 필터 교체');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
        }));
        const row = db.prepare('SELECT * FROM memos WHERE user_id = ?').get('U_TEST') as { content: string; is_shared: number };
        expect(row.content).toBe('정수기 필터 교체');
        expect(row.is_shared).toBe(1);
    });

    test('빈 내용이면 안내 메시지, DB 저장 안 됨', async () => {
        const respond = await app.trigger('/메모', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
        expect(db.prepare('SELECT * FROM memos').get()).toBeUndefined();
    });
});

describe('/메모목록', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U_TEST', 0, '개인 메모');
        db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U2', 1, '공유 메모');
    });

    test('필터 없으면 본인 + 공유 메모 포함', async () => {
        const respond = await app.trigger('/메모목록', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('개인 메모');
        expect(text).toContain('공유 메모');
    });

    test('"공유" 필터 → 공유 메모만', async () => {
        const respond = await app.trigger('/메모목록', '공유');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('공유 메모');
        expect(text).not.toContain('개인 메모');
    });
});

describe('/메모검색', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U_TEST', 0, '치과 예약 메모');
        db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U_TEST', 0, '장보기 목록');
    });

    test('검색어로 메모 찾기', async () => {
        const respond = await app.trigger('/메모검색', '치과');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('치과');
        expect(text).not.toContain('장보기');
    });

    test('빈 검색어 → 안내 메시지', async () => {
        const respond = await app.trigger('/메모검색', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('검색어'),
        }));
    });
});
