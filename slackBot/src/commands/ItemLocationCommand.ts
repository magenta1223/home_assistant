import type { App } from '@slack/bolt';
import type { SlackResponse, ItemLocationRow } from '../types';
import { UpsertCommand } from '../core/UpsertCommand';

export class ItemLocationCommand extends UpsertCommand {
    protected readonly tableName = 'item_locations';
    protected readonly nameCol = 'item_name';
    protected readonly valueCol = 'location';

    register(app: App): void {
        app.command('/위치저장', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.setLocation(command.text.trim(), command.user_id) as never);
        });

        app.command('/위치', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.getLocation(command.text.trim()) as never);
        });
    }

    private setLocation(text: string, userId: string): SlackResponse {
        if (!text) return this.err('물건명과 위치를 입력해주세요. 예: `/위치저장 리모컨 소파 옆`');

        const pair = this.splitTwo(text);
        if (!pair) return this.err('위치도 함께 입력해주세요. 예: `/위치저장 리모컨 소파 옆`');

        const [item, location] = pair;
        this.performUpsert(item, location, userId);

        return { text: `*${item}* 위치 저장: ${location}`, response_type: 'in_channel' };
    }

    private getLocation(item: string): SlackResponse {
        if (!item) return this.err('물건명을 입력해주세요. 예: `/위치 리모컨`');

        const row = this.db.prepare('SELECT * FROM item_locations WHERE item_name = ?').get(item) as ItemLocationRow | undefined;
        if (!row) return this.err(`*${item}* 위치 정보가 없어요.`);

        return {
            text: `*${row.item_name}*: ${row.location}  _(${row.updated_at} 기준)_`,
            response_type: 'ephemeral',
        };
    }
}
