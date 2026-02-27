import type { App } from '@slack/bolt';
import type { SlackResponse, TodoRow } from '../types';
import { SharedableCommand } from '../core/SharedableCommand';
import { listToBlocks } from '../formatters/blocks';

export class TodoCommand extends SharedableCommand {
    register(app: App): void {
        app.command('/할일', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.addTodo(command.text.trim(), command.user_id) as never);
        });

        app.command('/할일목록', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.listTodos(command.text.trim(), command.user_id) as never);
        });

        app.command('/완료', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.completeTodo(command.text.trim(), command.user_id) as never);
        });
    }

    private addTodo(text: string, userId: string): SlackResponse {
        if (!text) return this.err('할 일 내용을 입력해주세요. 예: `/할일 장보기`');

        const { isShared, content } = this.parseShared(text);
        this.db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run(userId, isShared ? 1 : 0, content);

        return {
            text: isShared ? `공유 할 일 추가: *${content}*` : `할 일 추가: *${content}*`,
            response_type: isShared ? 'in_channel' : 'ephemeral',
        };
    }

    private listTodos(filter: string, userId: string): SlackResponse {
        let rows: TodoRow[];
        if (filter === '공유') {
            rows = this.db.prepare('SELECT * FROM todos WHERE is_shared = 1 AND is_done = 0 ORDER BY created_at DESC').all() as TodoRow[];
        } else if (filter === '완료') {
            rows = this.db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 1 ORDER BY done_at DESC LIMIT 20').all(userId) as TodoRow[];
        } else {
            rows = this.db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 ORDER BY created_at DESC').all(userId) as TodoRow[];
        }

        const label = filter === '완료' ? '완료된 할 일' : (filter === '공유' ? '공유 할 일' : '할 일 목록');
        return {
            blocks: listToBlocks(label, rows, t => `${t.content}${t.is_shared ? ' _(공유)_' : ''}`),
            response_type: 'ephemeral',
        };
    }

    private completeTodo(hint: string, userId: string): SlackResponse {
        if (!hint) return this.err('완료할 할 일을 입력해주세요. 예: `/완료 장보기`');

        const row = this.db.prepare(
            'SELECT id FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 AND content LIKE ? LIMIT 1'
        ).get(userId, `%${hint}%`) as { id: number } | undefined;

        if (!row) return this.err(`"${hint}"에 해당하는 미완료 할 일을 찾지 못했어요.`);

        this.db.prepare("UPDATE todos SET is_done = 1, done_at = datetime('now','localtime') WHERE id = ?").run(row.id);
        return this.ok('완료 처리했어요!');
    }
}
