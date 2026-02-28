import type { Database } from 'better-sqlite3';

export function runMigrations(db: Database): void {
    db.exec(`
        CREATE TABLE IF NOT EXISTS memos (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id    TEXT    NOT NULL,
            is_shared  INTEGER NOT NULL DEFAULT 0,
            title      TEXT,
            content    TEXT    NOT NULL,
            tags       TEXT,
            created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
            updated_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );

        CREATE TABLE IF NOT EXISTS schedules (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     TEXT    NOT NULL,
            is_shared   INTEGER NOT NULL DEFAULT 0,
            title       TEXT    NOT NULL,
            description TEXT,
            event_date  TEXT    NOT NULL,
            end_date    TEXT,
            created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );

        CREATE TABLE IF NOT EXISTS home_status (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            device_name TEXT    NOT NULL,
            status      TEXT    NOT NULL,
            set_by      TEXT    NOT NULL,
            updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_home_status_device ON home_status(device_name);

        CREATE TABLE IF NOT EXISTS item_locations (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            item_name  TEXT    NOT NULL,
            location   TEXT    NOT NULL,
            set_by     TEXT    NOT NULL,
            updated_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_item_locations_name ON item_locations(item_name);

        CREATE TABLE IF NOT EXISTS assets (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     TEXT    NOT NULL,
            category    TEXT    NOT NULL DEFAULT 'cash',
            amount      REAL    NOT NULL,
            currency    TEXT    NOT NULL DEFAULT 'KRW',
            note        TEXT,
            recorded_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );

        CREATE TABLE IF NOT EXISTS todos (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id    TEXT    NOT NULL,
            is_shared  INTEGER NOT NULL DEFAULT 0,
            content    TEXT    NOT NULL,
            is_done    INTEGER NOT NULL DEFAULT 0,
            due_date   TEXT,
            created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
            done_at    TEXT
        );

        CREATE TABLE IF NOT EXISTS recipes (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     TEXT    NOT NULL,
            name        TEXT    NOT NULL,
            ingredients TEXT    NOT NULL,
            steps       TEXT    NOT NULL,
            tags        TEXT,
            servings    INTEGER,
            created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );

        DROP TABLE IF EXISTS grocery_transactions;
        DROP TABLE IF EXISTS grocery_items;

        CREATE TABLE IF NOT EXISTS grocery_items (
            id   INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT    NOT NULL UNIQUE,
            unit TEXT    NOT NULL DEFAULT '개'
        );

        CREATE TABLE IF NOT EXISTS grocery_purchases (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            item_id      INTEGER NOT NULL REFERENCES grocery_items(id),
            qty          REAL    NOT NULL,
            purchased_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
        );
    `);
}
