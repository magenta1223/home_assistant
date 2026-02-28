import Database from 'better-sqlite3';
import type { Database as DB } from 'better-sqlite3';
import * as sqliteVec from 'sqlite-vec';
import { runMigrations } from '../../src/db/migrate';

export function createTestDb(): DB {
    const db = new Database(':memory:');
    sqliteVec.load(db);
    db.pragma('foreign_keys = ON');
    runMigrations(db);
    return db;
}
