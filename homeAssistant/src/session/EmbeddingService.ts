import type { Database } from 'better-sqlite3';
import { CONTEXT_CONFIG } from '../config/context';

interface XenovaPipeline {
    (text: string, options: { pooling: string; normalize: boolean }): Promise<{ data: Float32Array | ArrayLike<number> }>;
}

interface SimilarResult {
    rowid: bigint;
    distance: number;
}

export class EmbeddingService {
    private static readonly ALLOWED_TABLES = new Set(['vec_memos', 'vec_todos', 'vec_recipes']);

    private pipelinePromise: Promise<XenovaPipeline> | null = null;

    constructor(private readonly db: Database) {}

    private assertTableAllowed(table: string): void {
        if (!EmbeddingService.ALLOWED_TABLES.has(table)) {
            throw new Error(`EmbeddingService: disallowed table "${table}"`);
        }
    }

    private getModel(): Promise<XenovaPipeline> {
        if (!this.pipelinePromise) {
            this.pipelinePromise = import('@xenova/transformers')
                .then(m => m.pipeline('feature-extraction', CONTEXT_CONFIG.embeddingModel) as Promise<XenovaPipeline>)
                .catch((err: unknown) => {
                    this.pipelinePromise = null;
                    return Promise.reject(err);
                });
        }
        return this.pipelinePromise;
    }

    async embed(text: string): Promise<Float32Array> {
        const model = await this.getModel();
        const output = await model(text, { pooling: 'mean', normalize: true });
        return output.data instanceof Float32Array
            ? output.data
            : new Float32Array(output.data);
    }

    store(table: string, rowId: number, embedding: Float32Array): void {
        this.assertTableAllowed(table);
        this.db.prepare(`DELETE FROM ${table} WHERE rowid = ?`).run(BigInt(rowId));
        this.db.prepare(`INSERT INTO ${table}(rowid, embedding) VALUES (?, ?)`).run(
            BigInt(rowId),
            Buffer.from(embedding.buffer),
        );
    }

    findSimilar(table: string, queryEmbedding: Float32Array, limit: number): SimilarResult[] {
        this.assertTableAllowed(table);
        return this.db.prepare(`
            SELECT rowid, distance
            FROM ${table}
            WHERE embedding MATCH ?
            ORDER BY distance
            LIMIT ?
        `).all(Buffer.from(queryEmbedding.buffer), limit) as SimilarResult[];
    }
}
