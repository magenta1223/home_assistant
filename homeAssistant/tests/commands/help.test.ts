import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { HelpCommand } from '../../src/commands/HelpCommand';

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new HelpCommand(db).register(app.asApp());
});

afterEach(() => { db.close(); });

describe('/도움말', () => {
    test('ephemeral blocks 응답 반환', async () => {
        const respond = await app.trigger('/도움말', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[]; response_type: string };
        expect(call.response_type).toBe('ephemeral');
        expect(call.blocks).toBeDefined();
        expect(call.blocks!.length).toBeGreaterThan(0);
    });

    test('주요 명령어 목록 포함', async () => {
        const respond = await app.trigger('/도움말', '');
        const call = respond.mock.calls[0][0] as { blocks?: unknown[] };
        const text = JSON.stringify(call.blocks);
        expect(text).toContain('/메모');
        expect(text).toContain('/할일');
        expect(text).toContain('/재고');
    });
});
