import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { RecipeCommand } from '../../src/commands/RecipeCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new RecipeCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/레시피저장', () => {
    test('레시피 저장 후 ephemeral 응답', async () => {
        const respond = await app.trigger('/레시피저장', '김치찌개\n재료: 김치, 돼지고기\n순서: 끓인다');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            response_type: 'ephemeral',
            text: expect.stringContaining('김치찌개'),
        }));
        const row = db.prepare('SELECT * FROM recipes WHERE name = ?').get('김치찌개') as { ingredients: string };
        expect(row).toBeDefined();
        expect(row.ingredients).toContain('김치');
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/레시피저장', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
    });
});

describe('/레시피', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)').run('U_TEST', '된장찌개', '된장, 두부', '끓인다');
    });

    test('레시피 이름으로 검색', async () => {
        const respond = await app.trigger('/레시피', '된장');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('된장찌개');
        expect(text).toContain('두부');
    });

    test('없는 레시피 → 안내 메시지', async () => {
        const respond = await app.trigger('/레시피', '파스타');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('찾지 못했어요'),
        }));
    });

    test('빈 입력 → 안내 메시지', async () => {
        const respond = await app.trigger('/레시피', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('입력해주세요'),
        }));
    });
});

describe('/레시피목록', () => {
    test('레시피 없으면 안내 메시지', async () => {
        const respond = await app.trigger('/레시피목록', '');
        expect(respond).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('없어요'),
        }));
    });

    test('레시피 목록 blocks 반환', async () => {
        db.prepare('INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)').run('U_TEST', '불고기', '소고기', '굽는다');
        db.prepare('INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)').run('U_TEST', '잡채', '당면', '볶는다');
        const respond = await app.trigger('/레시피목록', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('불고기');
        expect(text).toContain('잡채');
    });
});
