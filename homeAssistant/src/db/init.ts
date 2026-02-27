import Database from 'better-sqlite3';
import path from 'path';
import { runMigrations } from './migrate';

const DB_PATH = path.join(__dirname, '../../db/homeAssistant.sqlite');

const db = new Database(DB_PATH);

db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

runMigrations(db);

export default db;
