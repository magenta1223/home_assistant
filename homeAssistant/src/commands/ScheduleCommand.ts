import type { App } from '@slack/bolt';
import type { SlackResponse, ScheduleRow } from '../types';
import { SharedableCommand } from '../core/SharedableCommand';
import { listToBlocks } from '../formatters/blocks';
import { parseDate, parseDateRange } from '../nlp/claudeClient';

export class ScheduleCommand extends SharedableCommand {
    register(app: App): void {
        app.command('/일정', async ({ command, ack, respond }) => {
            await ack();
            try {
                await respond(await this.addSchedule(command.text.trim(), command.user_id) as never);
            } catch {
                await respond({ text: '날짜를 인식하지 못했어요. 예: `/일정 내일 오후 3시 치과`', response_type: 'ephemeral' } as never);
            }
        });

        app.command('/일정목록', async ({ command, ack, respond }) => {
            await ack();
            try {
                await respond(await this.listSchedules(command.text.trim(), command.user_id) as never);
            } catch {
                await respond({ text: '날짜 범위를 인식하지 못했어요. 예: `/일정목록 이번 주`', response_type: 'ephemeral' } as never);
            }
        });
    }

    private async addSchedule(text: string, userId: string): Promise<SlackResponse> {
        if (!text) return this.err('일정을 입력해주세요. 예: `/일정 내일 오후 3시 치과`');

        const { isShared, content } = this.parseShared(text);
        const eventDate = await parseDate(content);
        if (!eventDate) return this.err('날짜/시간 정보를 찾지 못했어요. 날짜를 포함해서 입력해주세요.');

        this.db.prepare('INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)').run(userId, isShared ? 1 : 0, content, eventDate);

        return {
            text: isShared
                ? `공유 일정 등록: *${content}* (${eventDate})`
                : `일정 등록: *${content}* (${eventDate})`,
            response_type: isShared ? 'in_channel' : 'ephemeral',
        };
    }

    private async listSchedules(text: string, userId: string): Promise<SlackResponse> {
        let from: string | null;
        let to: string | null;

        if (text) {
            ({ from, to } = await parseDateRange(text));
        } else {
            const today = new Date();
            from = today.toISOString().slice(0, 10);
            const future = new Date(today);
            future.setDate(future.getDate() + 30);
            to = future.toISOString().slice(0, 10);
        }

        const rows = this.db.prepare(`
            SELECT * FROM schedules
            WHERE (user_id = ? OR is_shared = 1)
              AND date(event_date) BETWEEN ? AND ?
            ORDER BY event_date ASC
        `).all(userId, from, to) as ScheduleRow[];

        const label = text ? `일정 (${text})` : '일정 (다음 30일)';
        return {
            blocks: listToBlocks(label, rows, s => {
                const shared = s.is_shared ? ' _(공유)_' : '';
                return `*${s.title}*${shared}\n_${s.event_date}_`;
            }),
            response_type: 'ephemeral',
        };
    }
}
