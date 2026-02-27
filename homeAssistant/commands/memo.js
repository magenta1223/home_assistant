const { listToBlocks } = require('../formatters/blocks');

function addMemo(db, userId, text) {
    if (!text) return { text: '내용을 입력해주세요. 예: `/메모 치과 예약 내일 3시`', response_type: 'ephemeral' };

    const isShared = text.startsWith('공유 ');
    const content = isShared ? text.slice(3).trim() : text;

    db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run(userId, isShared ? 1 : 0, content);

    return {
        text: isShared ? `공유 메모 저장했어요: _${content}_` : '메모 저장했어요!',
        response_type: isShared ? 'in_channel' : 'ephemeral',
    };
}

function listMemos(db, userId, filter) {
    let rows;
    if (filter === '공유') {
        rows = db.prepare('SELECT * FROM memos WHERE is_shared = 1 ORDER BY created_at DESC LIMIT 15').all();
    } else {
        rows = db.prepare('SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) ORDER BY created_at DESC LIMIT 15').all(userId);
    }

    const label = filter === '공유' ? '공유 메모' : '내 메모';
    const blocks = listToBlocks(label, rows, m => {
        const shared = m.is_shared ? ' _(공유)_' : '';
        const title = m.title ? `*${m.title}*\n` : '';
        return `${title}${m.content}${shared}\n_${m.created_at}_`;
    });
    return { blocks, response_type: 'ephemeral' };
}

function searchMemos(db, userId, query) {
    if (!query) return { text: '검색어를 입력해주세요. 예: `/메모검색 치과`', response_type: 'ephemeral' };

    const rows = db.prepare(`
        SELECT * FROM memos
        WHERE (user_id = ? OR is_shared = 1)
          AND (title LIKE ? OR content LIKE ? OR tags LIKE ?)
        ORDER BY created_at DESC LIMIT 10
    `).all(userId, `%${query}%`, `%${query}%`, `%${query}%`);

    const blocks = listToBlocks(`"${query}" 검색 결과`, rows, m => {
        const shared = m.is_shared ? ' _(공유)_' : '';
        return `${m.content}${shared}\n_${m.created_at}_`;
    });
    return { blocks, response_type: 'ephemeral' };
}

function register(app, db) {
    app.command('/메모', async ({ command, ack, respond }) => {
        await ack();
        await respond(addMemo(db, command.user_id, command.text.trim()));
    });

    app.command('/메모목록', async ({ command, ack, respond }) => {
        await ack();
        await respond(listMemos(db, command.user_id, command.text.trim()));
    });

    app.command('/메모검색', async ({ command, ack, respond }) => {
        await ack();
        await respond(searchMemos(db, command.user_id, command.text.trim()));
    });
}

module.exports = { register, addMemo, listMemos, searchMemos };
