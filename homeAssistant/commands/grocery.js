const { headerBlock, sectionBlock, divider } = require('../formatters/blocks');

const QTY_REGEX = /^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$/;

function parseItem(text) {
    const m = text.match(QTY_REGEX);
    if (!m) return null;
    return { name: m[1].trim(), qty: parseFloat(m[2]), unit: m[3] };
}

function getOrCreateItem(db, name, unit) {
    let row = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(name);
    if (!row) {
        db.prepare('INSERT INTO grocery_items (name, unit) VALUES (?, ?)').run(name, unit);
        row = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(name);
    }
    return row;
}

function addPurchase(db, userId, text) {
    const parsed = parseItem(text.trim());
    if (!parsed) return { text: '형식: `/구매 달걀 10개`  (수량+단위 필수)', response_type: 'ephemeral' };

    const item = getOrCreateItem(db, parsed.name, parsed.unit);
    const newQty = item.current_qty + parsed.qty;

    db.prepare("UPDATE grocery_items SET current_qty = ?, updated_at = datetime('now','localtime') WHERE id = ?").run(newQty, item.id);
    db.prepare('INSERT INTO grocery_transactions (item_id, user_id, delta) VALUES (?, ?, ?)').run(item.id, userId, parsed.qty);

    return {
        text: `*${parsed.name}* ${parsed.qty}${parsed.unit} 구매 기록. 현재 재고: ${newQty}${parsed.unit}`,
        response_type: 'in_channel',
    };
}

function addUsage(db, userId, text) {
    const parsed = parseItem(text.trim());
    if (!parsed) return { text: '형식: `/사용 달걀 3개`  (수량+단위 필수)', response_type: 'ephemeral' };

    const item = getOrCreateItem(db, parsed.name, parsed.unit);
    const newQty = Math.max(0, item.current_qty - parsed.qty);

    db.prepare("UPDATE grocery_items SET current_qty = ?, updated_at = datetime('now','localtime') WHERE id = ?").run(newQty, item.id);
    db.prepare('INSERT INTO grocery_transactions (item_id, user_id, delta) VALUES (?, ?, ?)').run(item.id, userId, -parsed.qty);

    return {
        text: `*${parsed.name}* ${parsed.qty}${parsed.unit} 사용 기록. 현재 재고: ${newQty}${parsed.unit}`,
        response_type: 'in_channel',
    };
}

function getInventory(db) {
    const items = db.prepare('SELECT * FROM grocery_items ORDER BY name').all();
    if (!items.length) return { text: '기록된 식재료가 없어요. `/구매 달걀 10개` 로 추가해보세요.', response_type: 'ephemeral' };

    const usageStmt = db.prepare(`
        SELECT ABS(SUM(delta)) as total_used, COUNT(DISTINCT date(recorded_at)) as days
        FROM grocery_transactions
        WHERE item_id = ? AND delta < 0
          AND recorded_at >= datetime('now', '-30 days', 'localtime')
    `);

    const low = [], ok = [];
    items.forEach(item => {
        const usage = usageStmt.get(item.id);
        const dailyUse = usage.days > 0 ? usage.total_used / usage.days : 0;
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

function register(app, db) {
    app.command('/구매', async ({ command, ack, respond }) => {
        await ack();
        await respond(addPurchase(db, command.user_id, command.text.trim()));
    });

    app.command('/사용', async ({ command, ack, respond }) => {
        await ack();
        await respond(addUsage(db, command.user_id, command.text.trim()));
    });

    app.command('/재고', async ({ command, ack, respond }) => {
        await ack();
        await respond(getInventory(db));
    });
}

module.exports = { register, parseItem, addPurchase, addUsage, getInventory };
