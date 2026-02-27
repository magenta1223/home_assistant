const { createTestDb } = require('../helpers/db');
const { saveRecipe, getRecipe, listRecipes } = require('../../commands/recipe');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('saveRecipe', () => {
    test('레시피 저장 후 ephemeral 응답', () => {
        const res = saveRecipe(db, 'U1', '김치찌개\n재료: 김치, 돼지고기\n순서: 1. 끓인다');
        expect(res.response_type).toBe('ephemeral');
        expect(res.text).toContain('김치찌개');

        const row = db.prepare('SELECT * FROM recipes WHERE user_id = ?').get('U1');
        expect(row.name).toBe('김치찌개');
        expect(row.ingredients).toContain('김치');
        expect(row.steps).toContain('끓인다');
    });

    test('재료/순서 없이 이름만 입력해도 저장됨', () => {
        saveRecipe(db, 'U1', '라면');
        const row = db.prepare('SELECT * FROM recipes WHERE name = ?').get('라면');
        expect(row).not.toBeUndefined();
    });

    test('빈 입력 시 안내 메시지 반환', () => {
        const res = saveRecipe(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
    });
});

describe('getRecipe', () => {
    beforeEach(() => {
        saveRecipe(db, 'U1', '김치찌개\n재료: 김치\n순서: 끓인다');
    });

    test('이름으로 레시피 조회 시 blocks 반환', () => {
        const res = getRecipe(db, '김치찌개');
        expect(res.blocks).toBeDefined();
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('김치찌개');
        expect(text).toContain('김치');
        expect(text).toContain('끓인다');
    });

    test('부분 일치로도 조회됨', () => {
        const res = getRecipe(db, '김치');
        expect(res.blocks).toBeDefined();
    });

    test('없는 레시피 조회 시 안내 메시지', () => {
        const res = getRecipe(db, '비빔밥');
        expect(res.text).toContain('찾지 못했어요');
    });

    test('빈 검색어 시 안내 메시지', () => {
        const res = getRecipe(db, '');
        expect(res.text).toContain('입력해주세요');
    });
});

describe('listRecipes', () => {
    test('저장된 레시피 없을 때 안내 메시지', () => {
        const res = listRecipes(db);
        expect(res.text).toContain('없어요');
    });

    test('레시피 있으면 목록 blocks 반환', () => {
        saveRecipe(db, 'U1', '김치찌개\n재료: 김치');
        saveRecipe(db, 'U1', '된장찌개\n재료: 된장');
        const res = listRecipes(db);
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('김치찌개');
        expect(text).toContain('된장찌개');
    });
});
