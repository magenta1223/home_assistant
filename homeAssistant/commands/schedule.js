const { parseDate, parseDateRange } = require('../nlp/claudeClient');
const { listToBlocks } = require('../formatters/blocks');

async function addSchedule(db, userId, text) {
    if (!text) return { text: '일정을 입력해주세요. 예: `/일정 내일 오후 3시 치과`', response_type: 'ephemeral' };

    const isShared = text.startsWith('공유 ');
    const content = isShared ? text.slice(3).trim() : text;

    const eventDate = await parseDate(content);
    if (!eventDate) return { text: '날짜/시간 정보를 찾지 못했어요. 날짜를 포함해서 입력해주세요.', response_type: 'ephemeral' };

    db.prepare('INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)').run(userId, isShared ? 1 : 0, content, eventDate);

    return {
        text: isShared
            ? `공유 일정 등록: *${content}* (${eventDate})`
            : `일정 등록: *${content}* (${eventDate})`,
        response_type: isShared ? 'in_channel' : 'ephemeral',
    };
}

async function listSchedules(db, userId, text) {
    let from, to;
    if (text) {
        ({ from, to } = await parseDateRange(text));
    } else {
        const today = new Date();
        from = today.toISOString().slice(0, 10);
        const future = new Date(today);
        future.setDate(future.getDate() + 30);
        to = future.toISOString().slice(0, 10);
    }

    const rows = db.prepare(`
        SELECT * FROM schedules
        WHERE (user_id = ? OR is_shared = 1)
          AND date(event_date) BETWEEN ? AND ?
        ORDER BY event_date ASC
    `).all(userId, from, to);

    const label = text ? `일정 (${text})` : '일정 (다음 30일)';
    const blocks = listToBlocks(label, rows, s => {
        const shared = s.is_shared ? ' _(공유)_' : '';
        return `*${s.title}*${shared}\n_${s.event_date}_`;
    });
    return { blocks, response_type: 'ephemeral' };
}

function register(app, db) {
    app.command('/일정', async ({ command, ack, respond }) => {
        await ack();
        try {
            await respond(await addSchedule(db, command.user_id, command.text.trim()));
        } catch {
            await respond({ text: '날짜를 인식하지 못했어요. 예: `/일정 내일 오후 3시 치과`', response_type: 'ephemeral' });
        }
    });

    app.command('/일정목록', async ({ command, ack, respond }) => {
        await ack();
        try {
            await respond(await listSchedules(db, command.user_id, command.text.trim()));
        } catch {
            await respond({ text: '날짜 범위를 인식하지 못했어요. 예: `/일정목록 이번 주`', response_type: 'ephemeral' });
        }
    });
}

module.exports = { register, addSchedule, listSchedules };
