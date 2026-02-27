import type { App } from '@slack/bolt';
import type { SlackResponse, ParsedGroceryItem, GroceryItemRow } from '../types';
import { BaseCommand } from '../core/BaseCommand';
import { headerBlock, sectionBlock, divider } from '../formatters/blocks';

const QTY_REGEX = /^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$/;

export class GroceryCommand extends BaseCommand {
    register(app: App): void {
        app.command('/구매', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.addPurchase(command.text.trim(), command.user_id) as never);
        });

        app.command('/사용', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.addUsage(command.text.trim(), command.user_id) as never);
        });

        app.command('/재고', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.getInventory() as never);
        });
    }

    private static parseItem(text: string): ParsedGroceryItem | null {
        const m = text.match(QTY_REGEX);
        if (!m) return null;
        return { name: (m[1] ?? '').trim(), qty: parseFloat(m[2] ?? '0'), unit: m[3] ?? '' };
    }

    private getOrCreateItem(name: string, unit: string): GroceryItemRow {
        let row = this.db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(name) as GroceryItemRow | undefined;
        if (!row) {
            this.db.prepare('INSERT INTO grocery_items (name, unit) VALUES (?, ?)').run(name, unit);
            row = this.db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(name) as GroceryItemRow;
        }
        return row;
    }

    private addPurchase(text: string, userId: string): SlackResponse {
        const parsed = GroceryCommand.parseItem(text);
        if (!parsed) return this.err('형식: `/구매 달걀 10개`  (수량+단위 필수)');

        const item = this.getOrCreateItem(parsed.name, parsed.unit);
        const newQty = item.current_qty + parsed.qty;

        this.db.prepare("UPDATE grocery_items SET current_qty = ?, updated_at = datetime('now','localtime') WHERE id = ?").run(newQty, item.id);
        this.db.prepare('INSERT INTO grocery_transactions (item_id, user_id, delta) VALUES (?, ?, ?)').run(item.id, userId, parsed.qty);

        return {
            text: `*${parsed.name}* ${parsed.qty}${parsed.unit} 구매 기록. 현재 재고: ${newQty}${parsed.unit}`,
            response_type: 'in_channel',
        };
    }

    private addUsage(text: string, userId: string): SlackResponse {
        const parsed = GroceryCommand.parseItem(text);
        if (!parsed) return this.err('형식: `/사용 달걀 3개`  (수량+단위 필수)');

        const item = this.getOrCreateItem(parsed.name, parsed.unit);
        const newQty = Math.max(0, item.current_qty - parsed.qty);

        this.db.prepare("UPDATE grocery_items SET current_qty = ?, updated_at = datetime('now','localtime') WHERE id = ?").run(newQty, item.id);
        this.db.prepare('INSERT INTO grocery_transactions (item_id, user_id, delta) VALUES (?, ?, ?)').run(item.id, userId, -parsed.qty);

        return {
            text: `*${parsed.name}* ${parsed.qty}${parsed.unit} 사용 기록. 현재 재고: ${newQty}${parsed.unit}`,
            response_type: 'in_channel',
        };
    }

    private getInventory(): SlackResponse {
        const items = this.db.prepare('SELECT * FROM grocery_items ORDER BY name').all() as GroceryItemRow[];
        if (!items.length) return this.err('기록된 식재료가 없어요. `/구매 달걀 10개` 로 추가해보세요.');

        const usageStmt = this.db.prepare(`
            SELECT ABS(SUM(delta)) as total_used, COUNT(DISTINCT date(recorded_at)) as days
            FROM grocery_transactions
            WHERE item_id = ? AND delta < 0
              AND recorded_at >= datetime('now', '-30 days', 'localtime')
        `);

        const low: string[] = [];
        const ok: string[] = [];

        items.forEach(item => {
            const usage = usageStmt.get(item.id) as { total_used: number | null; days: number };
            const totalUsed = usage.total_used ?? 0;
            const dailyUse = usage.days > 0 ? totalUsed / usage.days : 0;
            const daysLeft = dailyUse > 0 ? Math.floor(item.current_qty / dailyUse) : null;
            const shortage = item.current_qty <= item.min_qty;

            const line = shortage
                ? `⚠️ *${item.name}*: ${item.current_qty}${item.unit} (최소 ${item.min_qty}${item.unit})${daysLeft !== null ? ` — 약 ${daysLeft}일치` : ''}`
                : `✅ *${item.name}*: ${item.current_qty}${item.unit}${daysLeft !== null ? ` — 약 ${daysLeft}일치` : ''}`;

            shortage ? low.push(line) : ok.push(line);
        });

        const blocks = [headerBlock('재고 현황')];
        if (low.length) {
            blocks.push(sectionBlock('*부족한 식재료*'));
            blocks.push(sectionBlock(low.join('\n')));
            blocks.push(divider());
        }
        if (ok.length) {
            blocks.push(sectionBlock('*정상 재고*'));
            blocks.push(sectionBlock(ok.join('\n')));
        }
        return { blocks, response_type: 'ephemeral' };
    }
}
