const Database = require('better-sqlite3');
const path = require('path');
const { runMigrations } = require('./migrate');

const DB_PATH = path.join(__dirname, 'homeAssistant.sqlite');

const db = new Database(DB_PATH);

db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

runMigrations(db);

module.exports = db;
