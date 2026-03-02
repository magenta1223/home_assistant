import type { Database } from 'better-sqlite3';
import type { App } from '@slack/bolt';
import type { SlackResponse } from '../types';
import type { ICommand } from './ICommand';
import { headerBlock, sectionBlock, divider } from '../formatters/blocks';

export abstract class BaseCommand implements ICommand {
    constructor(protected readonly db: Database) {}

    abstract register(app: App): void;

    protected ok(text: string, inChannel = false): SlackResponse {
        return { text, response_type: inChannel ? 'in_channel' : 'ephemeral' };
    }

    protected err(text: string): SlackResponse {
        return { text, response_type: 'ephemeral' };
    }

    protected blocks(title: string, lines: string | string[]): SlackResponse {
        const content = Array.isArray(lines) ? lines.join('\n') : lines;
        return {
            blocks: [headerBlock(title), sectionBlock(content)],
            response_type: 'ephemeral',
        };
    }

    protected blocksWithTotal(title: string, lines: string, total: string): SlackResponse {
        return {
            blocks: [headerBlock(title), sectionBlock(lines), divider(), sectionBlock(total)],
            response_type: 'ephemeral',
        };
    }
}
