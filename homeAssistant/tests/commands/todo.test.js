const { createTestDb } = require('../helpers/db');
const { addTodo, listTodos, completeTodo } = require('../../commands/todo');

let db;
beforeEach(() => { db = createTestDb(); });
afterEach(() => { db.close(); });

describe('addTodo', () => {
    test('개인 할 일 저장 후 ephemeral 응답 반환', () => {
        const res = addTodo(db, 'U1', '장보기');
        expect(res.response_type).toBe('ephemeral');
        expect(res.text).toContain('장보기');

        const row = db.prepare('SELECT * FROM todos WHERE user_id = ?').get('U1');
        expect(row.content).toBe('장보기');
        expect(row.is_shared).toBe(0);
    });

    test('"공유 " 접두사 붙이면 is_shared=1, in_channel 응답', () => {
        const res = addTodo(db, 'U1', '공유 분리수거');
        expect(res.response_type).toBe('in_channel');

        const row = db.prepare('SELECT * FROM todos WHERE user_id = ?').get('U1');
        expect(row.content).toBe('분리수거');
        expect(row.is_shared).toBe(1);
    });

    test('빈 내용이면 안내 메시지 반환, DB 저장 안 됨', () => {
        const res = addTodo(db, 'U1', '');
        expect(res.text).toContain('입력해주세요');
        const row = db.prepare('SELECT * FROM todos').get();
        expect(row).toBeUndefined();
    });
});

describe('listTodos', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U1', 0, '개인 할일');
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U2', 1, '공유 할일');
        db.prepare("INSERT INTO todos (user_id, is_shared, content, is_done, done_at) VALUES (?, ?, ?, 1, datetime('now'))").run('U1', 0, '완료된 할일');
    });

    test('필터 없으면 본인 + 공유 미완료 항목 모두 반환', () => {
        const res = listTodos(db, 'U1', '');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('개인 할일');
        expect(text).toContain('공유 할일');
        expect(text).not.toContain('완료된 할일');
    });

    test('"공유" 필터 시 공유 항목만 반환', () => {
        const res = listTodos(db, 'U1', '공유');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('공유 할일');
        expect(text).not.toContain('개인 할일');
    });

    test('"완료" 필터 시 완료 항목만 반환', () => {
        const res = listTodos(db, 'U1', '완료');
        const text = JSON.stringify(res.blocks);
        expect(text).toContain('완료된 할일');
        expect(text).not.toContain('개인 할일');
    });
});

describe('completeTodo', () => {
    beforeEach(() => {
        db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run('U1', 0, '장보기');
    });

    test('hint로 항목 찾아 완료 처리', () => {
        const res = completeTodo(db, 'U1', '장보기');
        expect(res.text).toContain('완료');
        const row = db.prepare('SELECT * FROM todos WHERE content = ?').get('장보기');
        expect(row.is_done).toBe(1);
        expect(row.done_at).not.toBeNull();
    });

    test('부분 일치로도 완료 처리됨', () => {
        completeTodo(db, 'U1', '장보');
        const row = db.prepare('SELECT * FROM todos WHERE content = ?').get('장보기');
        expect(row.is_done).toBe(1);
    });

    test('없는 항목이면 안내 메시지 반환', () => {
        const res = completeTodo(db, 'U1', '없는항목');
        expect(res.text).toContain('찾지 못했어요');
    });
});
