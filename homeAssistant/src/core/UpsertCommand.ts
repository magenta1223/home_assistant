import { BaseCommand } from './BaseCommand';

export abstract class UpsertCommand extends BaseCommand {
    protected abstract readonly tableName: string;
    protected abstract readonly nameCol: string;
    protected abstract readonly valueCol: string;

    protected splitTwo(text: string): [string, string] | null {
        const parts = text.trim().split(/\s+/);
        if (parts.length < 2) return null;
        return [parts[0]!, parts.slice(1).join(' ')];
    }

    protected performUpsert(name: string, value: string, userId: string): void {
        this.db.prepare(
            `INSERT INTO ${this.tableName} (${this.nameCol}, ${this.valueCol}, set_by)
             VALUES (?, ?, ?)
             ON CONFLICT(${this.nameCol}) DO UPDATE SET
               ${this.valueCol} = excluded.${this.valueCol},
               set_by = excluded.set_by,
               updated_at = datetime('now','localtime')`
        ).run(name, value, userId);
    }
}
