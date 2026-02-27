import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { HomeStatusCommand } from '../../src/commands/HomeStatusCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new HomeStatusCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/상태', () => {
    test('기기 상태 저장 후 in_channel 응답', async () => {
        const respond = await app.trigger('/상태', '에어컨 켜');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
            text: expect.stringContaining('에어컨'),
        }));
        const row = db.prepare('SELECT * FROM home_status WHERE device_name = ?').get('에어컨') as { status: string };
        expect(row.status).toBe('켜');
    });

    test('같은 기기 두 번 설정 → upsert (최신값만)', async () => {
        await app.trigger('/상태', '에어컨 켜');
        await app.trigger('/상태', '에어컨 꺼');
        const rows = db.prepare('SELECT * FROM home_status WHERE device_name = ?').all('에어컨') as { status: string }[];
        expect(rows).toHaveLength(1);
        expect(rows[0]!.status).toBe('꺼');
    });

    test('기기명만 입력하면 안내 메시지', async () => {
        const respond = await app.trigger('/상태', '에어컨');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });

    test('빈 입력이면 안내 메시지', async () => {
        const respond = await app.trigger('/상태', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });
});

describe('/상태확인', () => {
    beforeEach(() => {
        db.prepare("INSERT INTO home_status (device_name, status, set_by) VALUES (?, ?, ?)").run('에어컨', '켜', 'U_TEST');
        db.prepare("INSERT INTO home_status (device_name, status, set_by) VALUES (?, ?, ?)").run('보일러', '꺼', 'U_TEST');
    });

    test('기기명 지정 → 해당 기기 상태 반환', async () => {
        const respond = await app.trigger('/상태확인', '에어컨');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('켜'),
        }));
    });

    test('기기명 없으면 전체 목록 blocks 반환', async () => {
        const respond = await app.trigger('/상태확인', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('에어컨');
        expect(text).toContain('보일러');
    });

    test('없는 기기 조회 → 안내 메시지', async () => {
        const respond = await app.trigger('/상태확인', '냉장고');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });
});
