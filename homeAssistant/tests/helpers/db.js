const Database = require('better-sqlite3');
const { runMigrations } = require('../../db/migrate');

function createTestDb() {
    const db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    runMigrations(db);
    return db;
}

module.exports = { createTestDb };
