import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { ItemLocationCommand } from '../../src/commands/ItemLocationCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new ItemLocationCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/위치저장', () => {
    test('위치 저장 후 in_channel 응답', async () => {
        const respond = await app.trigger('/위치저장', '리모컨 소파 옆');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'in_channel',
            text: expect.stringContaining('리모컨'),
        }));
        const row = db.prepare('SELECT * FROM item_locations WHERE item_name = ?').get('리모컨') as { location: string };
        expect(row.location).toBe('소파 옆');
    });

    test('같은 물건 두 번 저장 → upsert (최신값만)', async () => {
        await app.trigger('/위치저장', '리모컨 소파 옆');
        await app.trigger('/위치저장', '리모컨 TV 옆');
        const rows = db.prepare('SELECT * FROM item_locations WHERE item_name = ?').all('리모컨') as { location: string }[];
        expect(rows).toHaveLength(1);
        expect(rows[0]!.location).toBe('TV 옆');
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/위치저장', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });

    test('물건명만 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/위치저장', '리모컨');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });
});

describe('/위치', () => {
    beforeEach(() => {
        db.prepare("INSERT INTO item_locations (item_name, location, set_by) VALUES (?, ?, ?)").run('리모컨', '소파 옆', 'U_TEST');
    });

    test('물건 위치 조회', async () => {
        const respond = await app.trigger('/위치', '리모컨');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('소파 옆'),
        }));
    });

    test('없는 물건 조회 → 안내 메시지', async () => {
        const respond = await app.trigger('/위치', '충전기');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/위치', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
        }));
    });
});
