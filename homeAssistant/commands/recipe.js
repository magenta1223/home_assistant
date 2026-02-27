const { headerBlock, sectionBlock, divider } = require('../formatters/blocks');

function saveRecipe(db, userId, text) {
    if (!text) return { text: '레시피를 입력해주세요.\n형식: `/레시피저장 김치찌개\\n재료: 김치\\n순서: 끓인다`', response_type: 'ephemeral' };

    const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
    const name = lines[0];
    const body = lines.slice(1).join('\n');

    const ingredientsMatch = body.match(/재료[:：]?\s*([\s\S]*?)(?=순서[:：]|조리법[:：]|$)/i);
    const stepsMatch = body.match(/(?:순서|조리법)[:：]?\s*([\s\S]*)/i);

    const ingredients = ingredientsMatch ? ingredientsMatch[1].trim() : body;
    const steps = stepsMatch ? stepsMatch[1].trim() : '';

    db.prepare('INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)').run(userId, name, ingredients, steps);

    return { text: `레시피 *${name}* 저장했어요!`, response_type: 'ephemeral' };
}

function getRecipe(db, query) {
    if (!query) return { text: '레시피 이름을 입력해주세요. 예: `/레시피 김치찌개`', response_type: 'ephemeral' };

    const row = db.prepare('SELECT * FROM recipes WHERE name LIKE ? ORDER BY created_at DESC LIMIT 1').get(`%${query}%`);
    if (!row) return { text: `*${query}* 레시피를 찾지 못했어요.`, response_type: 'ephemeral' };

    const blocks = [
        headerBlock(`🍳 ${row.name}`),
        divider(),
        sectionBlock(`*재료*\n${row.ingredients}`),
    ];
    if (row.steps) {
        blocks.push(divider());
        blocks.push(sectionBlock(`*조리 순서*\n${row.steps}`));
    }
    return { blocks, response_type: 'ephemeral' };
}

function listRecipes(db) {
    const rows = db.prepare('SELECT id, name, created_at FROM recipes ORDER BY created_at DESC LIMIT 20').all();
    if (!rows.length) return { text: '저장된 레시피가 없어요.', response_type: 'ephemeral' };

    const list = rows.map((r, i) => `${i + 1}. *${r.name}*`).join('\n');
    return {
        blocks: [headerBlock('레시피 목록'), sectionBlock(list)],
        response_type: 'ephemeral',
    };
}

function register(app, db) {
    app.command('/레시피저장', async ({ command, ack, respond }) => {
        await ack();
        await respond(saveRecipe(db, command.user_id, command.text.trim()));
    });

    app.command('/레시피', async ({ command, ack, respond }) => {
        await ack();
        await respond(getRecipe(db, command.text.trim()));
    });

    app.command('/레시피목록', async ({ command, ack, respond }) => {
        await ack();
        await respond(listRecipes(db));
    });
}

module.exports = { register, saveRecipe, getRecipe, listRecipes };
