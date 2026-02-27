const { listToBlocks, headerBlock, sectionBlock, divider } = require('../formatters/blocks');

function recordAsset(db, userId, text) {
    if (!text) return { text: '카테고리와 금액을 입력해주세요. 예: `/자산 현금 500000`', response_type: 'ephemeral' };

    const parts = text.split(/\s+/);
    if (parts.length < 2) return { text: '금액도 함께 입력해주세요. 예: `/자산 현금 500000`', response_type: 'ephemeral' };

    const category = parts[0];
    const amount = parseFloat(parts[1].replace(/,/g, ''));
    const note = parts.slice(2).join(' ') || null;

    if (isNaN(amount)) return { text: '금액은 숫자로 입력해주세요. 예: `/자산 현금 500000`', response_type: 'ephemeral' };

    db.prepare('INSERT INTO assets (user_id, category, amount, note) VALUES (?, ?, ?, ?)').run(userId, category, amount, note);

    return {
        text: `*${category}* ${amount.toLocaleString('ko-KR')}원 기록했어요.`,
        response_type: 'ephemeral',
    };
}

function getAssets(db, userId) {
    const rows = db.prepare(`
        SELECT a.category, a.amount, a.recorded_at
        FROM assets a
        INNER JOIN (
            SELECT category, MAX(id) as max_id
            FROM assets WHERE user_id = ?
            GROUP BY category
        ) latest ON a.id = latest.max_id
        WHERE a.user_id = ?
        ORDER BY a.category
    `).all(userId, userId);

    if (!rows.length) return { text: '기록된 자산이 없어요. `/자산 현금 500000` 으로 추가해보세요.', response_type: 'ephemeral' };

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

function getAssetHistory(db, userId, category) {
    const rows = category
        ? db.prepare('SELECT * FROM assets WHERE user_id = ? AND category = ? ORDER BY recorded_at DESC LIMIT 20').all(userId, category)
        : db.prepare('SELECT * FROM assets WHERE user_id = ? ORDER BY recorded_at DESC LIMIT 20').all(userId);

    const label = category ? `${category} 내역` : '자산 내역';
    const blocks = listToBlocks(label, rows, r =>
        `*${r.category}*: ${r.amount.toLocaleString('ko-KR')}원${r.note ? ` (${r.note})` : ''}\n_${r.recorded_at}_`
    );
    return { blocks, response_type: 'ephemeral' };
}

function register(app, db) {
    app.command('/자산', async ({ command, ack, respond }) => {
        await ack();
        await respond(recordAsset(db, command.user_id, command.text.trim()));
    });

    app.command('/자산확인', async ({ command, ack, respond }) => {
        await ack();
        await respond(getAssets(db, command.user_id));
    });

    app.command('/자산내역', async ({ command, ack, respond }) => {
        await ack();
        await respond(getAssetHistory(db, command.user_id, command.text.trim()));
    });
}

module.exports = { register, recordAsset, getAssets, getAssetHistory };
