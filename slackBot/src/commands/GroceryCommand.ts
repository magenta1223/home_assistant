import type { App } from '@slack/bolt';
import type { SlackResponse, ParsedGroceryItem, GroceryItemRow, GroceryPurchaseRow } from '../types';
import { BaseCommand } from '../core/BaseCommand';
import { headerBlock, sectionBlock, divider } from '../formatters/blocks';

const QTY_REGEX = /^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$/;

export class GroceryCommand extends BaseCommand {
    register(app: App): void {
        app.command('/구매', async ({ command, ack, respond }) => {
            await ack();
            await respond(this.addPurchase(command.text.trim()) as never);
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

    private addPurchase(text: string): SlackResponse {
        const parsed = GroceryCommand.parseItem(text);
        if (!parsed) return this.err('형식: `/구매 달걀 10개`  (수량+단위 필수)');

        const item = this.getOrCreateItem(parsed.name, parsed.unit);
        this.db.prepare('INSERT INTO grocery_purchases (item_id, qty) VALUES (?, ?)').run(item.id, parsed.qty);

        return {
            text: `*${parsed.name}* ${parsed.qty}${parsed.unit} 구매 기록 완료`,
            response_type: 'in_channel',
        };
    }

    private getInventory(): SlackResponse {
        const items = this.db.prepare('SELECT * FROM grocery_items ORDER BY name').all() as GroceryItemRow[];
        if (!items.length) return this.err('기록된 식재료가 없어요. `/구매 달걀 10개` 로 추가해보세요.');

        const purchaseStmt = this.db.prepare(
            'SELECT purchased_at FROM grocery_purchases WHERE item_id = ? ORDER BY purchased_at ASC'
        );

        const shortage: string[] = [];
        const imminent: string[] = [];
        const ok: string[] = [];
        const insufficient: string[] = [];

        items.forEach(item => {
            const purchases = purchaseStmt.all(item.id) as Pick<GroceryPurchaseRow, 'purchased_at'>[];

            if (purchases.length < 2) {
                insufficient.push(`📊 *${item.name}*: 구매 이력 부족 (${purchases.length}회) — 예측 불가`);
                return;
            }

            const dates = purchases.map(p => new Date(p.purchased_at).getTime());
            let totalInterval = 0;
            for (let i = 1; i < dates.length; i++) {
                totalInterval += (dates[i]! - dates[i - 1]!) / (1000 * 60 * 60 * 24);
            }
            const avgInterval = totalInterval / (dates.length - 1);
            const lastDate = new Date(purchases[purchases.length - 1]!.purchased_at).getTime();
            const daysSinceLast = (Date.now() - lastDate) / (1000 * 60 * 60 * 24);
            const daysRemaining = Math.round(avgInterval - daysSinceLast);

            if (daysRemaining <= 0) {
                shortage.push(`⚠️ *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
            } else if (daysRemaining <= 3) {
                imminent.push(`🔔 *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
            } else {
                ok.push(`✅ *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
            }
        });

        const blocks = [headerBlock('재고 현황')];

        if (shortage.length) {
            blocks.push(sectionBlock('*부족 예상*'));
            blocks.push(sectionBlock(shortage.join('\n')));
            blocks.push(divider());
        }
        if (imminent.length) {
            blocks.push(sectionBlock('*구매 임박*'));
            blocks.push(sectionBlock(imminent.join('\n')));
            blocks.push(divider());
        }
        if (ok.length) {
            blocks.push(sectionBlock('*여유 있음*'));
            blocks.push(sectionBlock(ok.join('\n')));
        }
        if (insufficient.length) {
            if (ok.length || imminent.length || shortage.length) blocks.push(divider());
            blocks.push(sectionBlock('*데이터 부족*'));
            blocks.push(sectionBlock(insufficient.join('\n')));
        }

        return { blocks, response_type: 'ephemeral' };
    }
}
