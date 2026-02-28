import type { Database } from 'better-sqlite3';
import type { ContextSpec, ContextResult, FilterParams } from '../types';
import { EmbeddingService } from './EmbeddingService';
import { CONTEXT_CONFIG } from '../config/context';

const TABLE_META: Record<string, {
    textCol: string;
    hasUserId: boolean;
    dateCol?: string;
    vecTable?: string;
}> = {
    memos:          { textCol: 'content',     hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_memos'   },
    todos:          { textCol: 'content',     hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_todos'   },
    schedules:      { textCol: 'title',       hasUserId: true,  dateCol: 'event_date'                         },
    home_status:    { textCol: 'device_name', hasUserId: false, dateCol: 'updated_at'                         },
    item_locations: { textCol: 'item_name',   hasUserId: false, dateCol: 'updated_at'                         },
    assets:         { textCol: 'category',    hasUserId: true,  dateCol: 'recorded_at'                        },
    recipes:        { textCol: 'name',        hasUserId: true,  dateCol: 'created_at', vecTable: 'vec_recipes' },
    grocery_items:  { textCol: 'name',        hasUserId: false                                                },
};

const ALLOWED_TABLES = new Set(Object.keys(TABLE_META));

export class ContextRetriever {
    constructor(
        private readonly db: Database,
        private readonly embedding: EmbeddingService,
    ) {}

    async retrieve(specs: ContextSpec[], userId: string): Promise<ContextResult[]> {
        return Promise.all(specs.map(spec => this.retrieveOne(spec, userId)));
    }

    async storeEmbedding(table: string, vecTable: string, rowId: number, text: string): Promise<void> {
        const embedding = await this.embedding.embed(text);
        this.embedding.store(vecTable, rowId, embedding);
    }

    private async retrieveOne(spec: ContextSpec, userId: string): Promise<ContextResult> {
        switch (spec.type) {
            case 'recent':  return this.retrieveRecent(spec.db, userId);
            case 'query':   return this.retrieveQuery(spec.db, userId, spec.filter ?? {});
            case 'similar': return this.retrieveSimilar(spec.db, userId, spec.searchText ?? '');
        }
    }

    private retrieveRecent(table: string, userId: string): ContextResult {
        if (!ALLOWED_TABLES.has(table)) return { db: table, type: 'recent', rows: [] };
        const meta = TABLE_META[table]!;
        const dateCol = meta.dateCol ?? 'rowid';
        const rows = meta.hasUserId
            ? this.db.prepare(
                `SELECT * FROM ${table} WHERE (user_id = ? OR is_shared = 1) ORDER BY ${dateCol} DESC LIMIT ?`
              ).all(userId, CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[]
            : this.db.prepare(
                `SELECT * FROM ${table} ORDER BY ${dateCol} DESC LIMIT ?`
              ).all(CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[];
        return { db: table, type: 'recent', rows };
    }

    private retrieveQuery(table: string, userId: string, filter: FilterParams): ContextResult {
        if (!ALLOWED_TABLES.has(table)) return { db: table, type: 'query', rows: [] };
        const meta = TABLE_META[table]!;
        const conditions: string[] = [];
        const params: unknown[] = [];

        if (meta.hasUserId) {
            conditions.push('(user_id = ? OR is_shared = 1)');
            params.push(userId);
        }
        if (filter.keyword) {
            conditions.push(`${meta.textCol} LIKE ?`);
            params.push(`%${filter.keyword}%`);
        }
        if (filter.dateFrom && meta.dateCol) {
            conditions.push(`date(${meta.dateCol}) >= ?`);
            params.push(filter.dateFrom);
        }
        if (filter.dateTo && meta.dateCol) {
            conditions.push(`date(${meta.dateCol}) <= ?`);
            params.push(filter.dateTo);
        }
        if (filter.category) {
            conditions.push('category = ?');
            params.push(filter.category);
        }
        if (filter.isShared !== undefined) {
            conditions.push('is_shared = ?');
            params.push(filter.isShared ? 1 : 0);
        }

        const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
        const rows = this.db.prepare(
            `SELECT * FROM ${table} ${where} LIMIT ?`
        ).all(...params, CONTEXT_CONFIG.recentLimit) as Record<string, unknown>[];
        return { db: table, type: 'query', rows };
    }

    private async retrieveSimilar(table: string, userId: string, searchText: string): Promise<ContextResult> {
        if (!ALLOWED_TABLES.has(table)) return { db: table, type: 'similar', rows: [] };
        const meta = TABLE_META[table]!;
        if (!meta.vecTable) return this.retrieveRecent(table, userId);

        const queryEmbedding = await this.embedding.embed(searchText);
        const similar = this.embedding.findSimilar(meta.vecTable, queryEmbedding, CONTEXT_CONFIG.similarLimit);
        if (!similar.length) return { db: table, type: 'similar', rows: [] };

        const ids = similar.map(s => Number(s.rowid));
        const placeholders = ids.map(() => '?').join(',');
        const rows = this.db.prepare(
            `SELECT * FROM ${table} WHERE id IN (${placeholders})`
        ).all(...ids) as Record<string, unknown>[];
        return { db: table, type: 'similar', rows };
    }
}
