import type { Database } from 'better-sqlite3';
import { CONTEXT_CONFIG } from '../config/context';

interface SimilarResult {
    rowid: number;
    distance: number;
}

export class EmbeddingService {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    private pipelinePromise: Promise<any> | null = null;

    constructor(private readonly db: Database) {}

    private getModel(): Promise<any> {
        if (!this.pipelinePromise) {
            this.pipelinePromise = import('@xenova/transformers').then(m =>
                m.pipeline('feature-extraction', CONTEXT_CONFIG.embeddingModel)
            );
        }
        return this.pipelinePromise!;
    }

    async embed(text: string): Promise<Float32Array> {
        const model = await this.getModel();
        const output = await model(text, { pooling: 'mean', normalize: true });
        return output.data instanceof Float32Array
            ? output.data
            : new Float32Array(output.data);
    }

    store(table: string, rowId: number, embedding: Float32Array): void {
        this.db.prepare(`DELETE FROM ${table} WHERE rowid = ?`).run(BigInt(rowId));
        this.db.prepare(`INSERT INTO ${table}(rowid, embedding) VALUES (?, ?)`).run(
            BigInt(rowId),
            Buffer.from(embedding.buffer),
        );
    }

    findSimilar(table: string, queryEmbedding: Float32Array, limit: number): SimilarResult[] {
        return this.db.prepare(`
            SELECT rowid, distance
            FROM ${table}
            WHERE embedding MATCH ?
            ORDER BY distance
            LIMIT ?
        `).all(Buffer.from(queryEmbedding.buffer), limit) as SimilarResult[];
    }
}
