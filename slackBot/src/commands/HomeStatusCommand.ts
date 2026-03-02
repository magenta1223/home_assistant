import type { App } from '@slack/bolt';
import type { SlackResponse, HomeStatusRow } from '../types';
import { UpsertCommand } from '../core/UpsertCommand';
import { listToBlocks } from '../formatters/blocks';

export class HomeStatusCommand extends UpsertCommand {
    protected readonly tableName = 'home_status';
    protected readonly nameCol = 'device_name';
    protected readonly valueCol = 'status';

    register(app: App): void {
        app.command('/상태', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.setStatus(command.text.trim(), command.user_id) as never);
        });

        app.command('/상태확인', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.getStatus(command.text.trim()) as never);
        });
    }

    private setStatus(text: string, userId: string): SlackResponse {
        if (!text) return this.err('기기명과 상태를 입력해주세요. 예: `/상태 에어컨 켜`');

        const pair = this.splitTwo(text);
        if (!pair) return this.err('상태도 함께 입력해주세요. 예: `/상태 에어컨 켜`');

        const [device, status] = pair;
        this.performUpsert(device, status, userId);

        return {
            text: `*${device}* 상태를 *${status}*(으)로 업데이트했어요.`,
            response_type: 'in_channel',
        };
    }

    private getStatus(device: string): SlackResponse {
        if (!device) {
            const rows = this.db.prepare('SELECT * FROM home_status ORDER BY device_name').all() as HomeStatusRow[];
            return {
                blocks: listToBlocks('집 전체 상태', rows, r => `*${r.device_name}*: ${r.status}  _(${r.updated_at})_`),
                response_type: 'ephemeral',
            };
        }

        const row = this.db.prepare('SELECT * FROM home_status WHERE device_name = ?').get(device) as HomeStatusRow | undefined;
        if (!row) return this.err(`*${device}* 상태 정보가 없어요.`);

        return {
            text: `*${row.device_name}*: ${row.status}  _(${row.updated_at} 기준)_`,
            response_type: 'ephemeral',
        };
    }
}
