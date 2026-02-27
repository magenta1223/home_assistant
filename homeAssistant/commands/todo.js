const { listToBlocks } = require('../formatters/blocks');

function addTodo(db, userId, text) {
    if (!text) return { text: '할 일 내용을 입력해주세요. 예: `/할일 장보기`', response_type: 'ephemeral' };

    const isShared = text.startsWith('공유 ');
    const content = isShared ? text.slice(3).trim() : text;

    db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run(userId, isShared ? 1 : 0, content);

    return {
        text: isShared ? `공유 할 일 추가: *${content}*` : `할 일 추가: *${content}*`,
        response_type: isShared ? 'in_channel' : 'ephemeral',
    };
}

function listTodos(db, userId, filter) {
    let rows;
    if (filter === '공유') {
        rows = db.prepare('SELECT * FROM todos WHERE is_shared = 1 AND is_done = 0 ORDER BY created_at DESC').all();
    } else if (filter === '완료') {
        rows = db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 1 ORDER BY done_at DESC LIMIT 20').all(userId);
    } else {
        rows = db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 ORDER BY created_at DESC').all(userId);
    }

    const label = filter === '완료' ? '완료된 할 일' : (filter === '공유' ? '공유 할 일' : '할 일 목록');
    const blocks = listToBlocks(label, rows, t => {
        const shared = t.is_shared ? ' _(공유)_' : '';
        return `${t.content}${shared}`;
    });
    return { blocks, response_type: 'ephemeral' };
}

function completeTodo(db, userId, hint) {
    if (!hint) return { text: '완료할 할 일을 입력해주세요. 예: `/완료 장보기`', response_type: 'ephemeral' };

    const row = db.prepare(
        'SELECT id FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 AND content LIKE ? LIMIT 1'
    ).get(userId, `%${hint}%`);

    if (!row) return { text: `"${hint}"에 해당하는 미완료 할 일을 찾지 못했어요.`, response_type: 'ephemeral' };

    db.prepare("UPDATE todos SET is_done = 1, done_at = datetime('now','localtime') WHERE id = ?").run(row.id);
    return { text: '완료 처리했어요!', response_type: 'ephemeral' };
}

function register(app, db) {
    app.command('/할일', async ({ command, ack, respond }) => {
        await ack();
        await respond(addTodo(db, command.user_id, command.text.trim()));
    });

    app.command('/할일목록', async ({ command, ack, respond }) => {
        await ack();
        await respond(listTodos(db, command.user_id, command.text.trim()));
    });

    app.command('/완료', async ({ command, ack, respond }) => {
        await ack();
        await respond(completeTodo(db, command.user_id, command.text.trim()));
    });
}

module.exports = { register, addTodo, listTodos, completeTodo };
