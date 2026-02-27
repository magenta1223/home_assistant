import type { Database } from 'better-sqlite3';
import type { App } from '@slack/bolt';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { TodoCommand } from '../../src/commands/TodoCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new TodoCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/할일', () => {
    test('개인 할일 추가 후 ephemeral 응답', async () => {
        const respond = await app.trigger('/할일', '장보기');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('장보기'),
        }));
        const row = db.prepare('SELECT * FROM todos WHERE user_id = ?').get('U_TEST') as { content: string; is_shared: number };
        expect(row.content).toBe('장보기');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 → is_shared=1, in_channel 응답', async () => {
        const respond = await app.trigger('/할일', '공유 분리수거');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
        }));
        const row = db.prepare('SELECT * FROM todos WHERE user_id = ?').get('U_TEST') as { content: string; is_shared: number };
        expect(row.content).toBe('분리수거');
        expect(row.is_shared).toBe(1);
    });

    test('빈 내용이면 안내 메시지, DB 저장 안 됨', async () => {
        const respond = await app.trigger('/할일', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('입력해주세요'),
        }));
        const row = db.prepare('SELECT * FROM todos').get();
        expect(row).toBeUndefined();
    });
});

describe('/할일목록', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U_TEST', 0, '개인 할일');
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U2', 1, '공유 할일');
        db.prepare("INSERT INTO todos (user_id, is_shared, content, is_done, done_at) VALUES (?, ?, ?, 1, datetime('now'))").run('U_TEST', 0, '완료된 할일');
    });

    test('필터 없으면 본인 + 공유 미완료 항목 포함', async () => {
        const respond = await app.trigger('/할일목록', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('개인 할일');
        expect(text).toContain('공유 할일');
        expect(text).not.toContain('완료된 할일');
    });

    test('"공유" 필터 → 공유 항목만', async () => {
        const respond = await app.trigger('/할일목록', '공유');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('공유 할일');
        expect(text).not.toContain('개인 할일');
    });

    test('"완료" 필터 → 완료 항목만', async () => {
        const respond = await app.trigger('/할일목록', '완료');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('완료된 할일');
        expect(text).not.toContain('개인 할일');
    });
});

describe('/완료', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U_TEST', 0, '장보기');
    });

    test('hint로 항목 찾아 완료 처리', async () => {
        const respond = await app.trigger('/완료', '장보기');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('완료'),
        }));
        const row = db.prepare('SELECT * FROM todos WHERE content = ?').get('장보기') as { is_done: number; done_at: string | null };
        expect(row.is_done).toBe(1);
        expect(row.done_at).not.toBeNull();
    });

    test('부분 일치로도 완료 처리', async () => {
        await app.trigger('/완료', '장보');
        const row = db.prepare('SELECT * FROM todos WHERE content = ?').get('장보기') as { is_done: number };
        expect(row.is_done).toBe(1);
    });

    test('없는 항목이면 안내 메시지', async () => {
        const respond = await app.trigger('/완료', '없는항목');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('찾지 못했어요'),
        }));
    });

    test('빈 내용이면 안내 메시지', async () => {
        const respond = await app.trigger('/완료', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
    });
});
