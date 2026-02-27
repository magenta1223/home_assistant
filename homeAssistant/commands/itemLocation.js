function setLocation(db, userId, text) {
    if (!text) return { text: '물건명과 위치를 입력해주세요. 예: `/위치저장 리모컨 소파 옆`', response_type: 'ephemeral' };

    const spaceIdx = text.indexOf(' ');
    if (spaceIdx === -1) return { text: '위치도 함께 입력해주세요. 예: `/위치저장 리모컨 소파 옆`', response_type: 'ephemeral' };

    const item = text.slice(0, spaceIdx).trim();
    const location = text.slice(spaceIdx + 1).trim();

    db.prepare(`
        INSERT INTO item_locations (item_name, location, set_by)
        VALUES (?, ?, ?)
        ON CONFLICT(item_name) DO UPDATE SET location = excluded.location, set_by = excluded.set_by, updated_at = datetime('now','localtime')
    `).run(item, location, userId);

    return { text: `*${item}* 위치 저장: ${location}`, response_type: 'in_channel' };
}

function getLocation(db, item) {
    if (!item) return { text: '물건명을 입력해주세요. 예: `/위치 리모컨`', response_type: 'ephemeral' };

    const row = db.prepare('SELECT * FROM item_locations WHERE item_name = ?').get(item);
    if (!row) return { text: `*${item}* 위치 정보가 없어요.`, response_type: 'ephemeral' };

    return {
        text: `*${row.item_name}*: ${row.location}  _(${row.updated_at} 기준)_`,
        response_type: 'ephemeral',
    };
}

function register(app, db) {
    app.command('/위치저장', async ({ command, ack, respond }) => {
        await ack();
        await respond(setLocation(db, command.user_id, command.text.trim()));
    });

    app.command('/위치', async ({ command, ack, respond }) => {
        await ack();
        await respond(getLocation(db, command.text.trim()));
    });
}

module.exports = { register, setLocation, getLocation };
