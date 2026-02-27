const { listToBlocks } = require('../formatters/blocks');

function setStatus(db, userId, text) {
    if (!text) return { text: '기기명과 상태를 입력해주세요. 예: `/상태 에어컨 켜`', response_type: 'ephemeral' };

    const spaceIdx = text.indexOf(' ');
    if (spaceIdx === -1) return { text: '상태도 함께 입력해주세요. 예: `/상태 에어컨 켜`', response_type: 'ephemeral' };

    const device = text.slice(0, spaceIdx).trim();
    const status = text.slice(spaceIdx + 1).trim();

    db.prepare(`
        INSERT INTO home_status (device_name, status, set_by)
        VALUES (?, ?, ?)
        ON CONFLICT(device_name) DO UPDATE SET status = excluded.status, set_by = excluded.set_by, updated_at = datetime('now','localtime')
    `).run(device, status, userId);

    return {
        text: `*${device}* 상태를 *${status}*(으)로 업데이트했어요.`,
        response_type: 'in_channel',
    };
}

function getStatus(db, device) {
    if (!device) {
        const rows = db.prepare('SELECT * FROM home_status ORDER BY device_name').all();
        const blocks = listToBlocks('집 전체 상태', rows, r => `*${r.device_name}*: ${r.status}  _(${r.updated_at})_`);
        return { blocks, response_type: 'ephemeral' };
    }

    const row = db.prepare('SELECT * FROM home_status WHERE device_name = ?').get(device);
    if (!row) return { text: `*${device}* 상태 정보가 없어요.`, response_type: 'ephemeral' };

    return {
        text: `*${row.device_name}*: ${row.status}  _(${row.updated_at} 기준)_`,
        response_type: 'ephemeral',
    };
}

function register(app, db) {
    app.command('/상태', async ({ command, ack, respond }) => {
        await ack();
        await respond(setStatus(db, command.user_id, command.text.trim()));
    });

    app.command('/상태확인', async ({ command, ack, respond }) => {
        await ack();
        await respond(getStatus(db, command.text.trim()));
    });
}

module.exports = { register, setStatus, getStatus };
