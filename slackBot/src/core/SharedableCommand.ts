import { BaseCommand } from './BaseCommand';
import type { ParsedShared } from '../types';

export abstract class SharedableCommand extends BaseCommand {
    protected parseShared(text: string): ParsedShared {
        if (text.startsWith('공유 ')) {
            return { isShared: true, content: text.slice(3).trim() };
        }
        return { isShared: false, content: text };
    }

    protected sharedWhereClause(userId: string): string {
        return `(user_id = '${userId}' OR is_shared = 1)`;
    }
}
