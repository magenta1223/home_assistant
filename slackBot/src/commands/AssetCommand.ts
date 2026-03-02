import type { App } from '@slack/bolt';
import type { SlackResponse, AssetRow } from '../types';
import { BaseCommand } from '../core/BaseCommand';
import { listToBlocks, headerBlock, sectionBlock, divider } from '../formatters/blocks';

export class AssetCommand extends BaseCommand {
    register(app: App): void {
        app.command('/자산', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.recordAsset(command.text.trim(), command.user_id) as never);
        });

        app.command('/자산확인', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.getAssets(command.user_id) as never);
        });

        app.command('/자산내역', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.getAssetHistory(command.text.trim(), command.user_id) as never);
        });
    }

    private recordAsset(text: string, userId: string): SlackResponse {
        if (!text) return this.err('카테고리와 금액을 입력해주세요. 예: `/자산 현금 500000`');

        const parts = text.split(/\s+/);
        if (parts.length < 2) return this.err('금액도 함께 입력해주세요. 예: `/자산 현금 500000`');

        const category = parts[0] ?? '';
        const amount = parseFloat((parts[1] ?? '').replace(/,/g, ''));
        const note = parts.slice(2).join(' ') || null;

        if (isNaN(amount)) return this.err('금액은 숫자로 입력해주세요. 예: `/자산 현금 500000`');

        this.db.prepare('INSERT INTO assets (user_id, category, amount, note) VALUES (?, ?, ?, ?)').run(userId, category, amount, note);

        return {
            text: `*${category}* ${amount.toLocaleString('ko-KR')}원 기록했어요.`,
            response_type: 'ephemeral',
        };
    }

    private getAssets(userId: string): SlackResponse {
        const rows = this.db.prepare(`
            SELECT a.category, a.amount, a.recorded_at
            FROM assets a
            INNER JOIN (
                SELECT category, MAX(id) as max_id
                FROM assets WHERE user_id = ?
                GROUP BY category
            ) latest ON a.id = latest.max_id
            WHERE a.user_id = ?
            ORDER BY a.category
        `).all(userId, userId) as Pick<AssetRow, 'category' | 'amount' | 'recorded_at'>[];

        if (!rows.length) return this.err('기록된 자산이 없어요. `/자산 현금 500000` 으로 추가해보세요.');

        const total = rows.reduce((sum, r) => sum + r.amount, 0);
        const lines = rows.map(r => `*${r.category}*: ${r.amount.toLocaleString('ko-KR')}원`).join('\n');

        return {
            blocks: [
                headerBlock('자산 현황'),
                sectionBlock(lines),
                divider(),
                sectionBlock(`*합계: ${total.toLocaleString('ko-KR')}원*`),
            ],
            response_type: 'ephemeral',
        };
    }

    private getAssetHistory(category: string, userId: string): SlackResponse {
        const rows = category
            ? this.db.prepare('SELECT * FROM assets WHERE user_id = ? AND category = ? ORDER BY recorded_at DESC LIMIT 20').all(userId, category) as AssetRow[]
            : this.db.prepare('SELECT * FROM assets WHERE user_id = ? ORDER BY recorded_at DESC LIMIT 20').all(userId) as AssetRow[];

        const label = category ? `${category} 내역` : '자산 내역';
        return {
            blocks: listToBlocks(label, rows, r =>
                `*${r.category}*: ${r.amount.toLocaleString('ko-KR')}원${r.note ? ` (${r.note})` : ''}\n_${r.recorded_at}_`
            ),
            response_type: 'ephemeral',
        };
    }
}
