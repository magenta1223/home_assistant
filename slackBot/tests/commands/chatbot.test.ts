import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { ChatbotCommand } from '../../src/commands/ChatbotCommand';
import { SessionManager } from '../../src/session/SessionManager';
import type { ContextRetriever } from '../../src/session/ContextRetriever';

jest.mock('../../src/nlp/claudeClient', () => ({
    chatSession: jest.fn(),
    analyzeIntent: jest.fn(),
    parseDate: jest.fn(),
    parseDateRange: jest.fn(),
}));

import { chatSession, analyzeIntent } from '../../src/nlp/claudeClient';

const mockChatSession = chatSession as jest.Mock;
const mockAnalyzeIntent = analyzeIntent as jest.Mock;

let db: Database;
let app: MockApp;
let sessions: SessionManager;
let mockRetriever: jest.Mocked<ContextRetriever>;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    sessions = new SessionManager(10 * 60 * 1000);
    mockRetriever = {
        retrieve: jest.fn().mockResolvedValue([]),
        storeEmbedding: jest.fn().mockResolvedValue(undefined),
    } as unknown as jest.Mocked<ContextRetriever>;
    new ChatbotCommand(db, sessions, mockRetriever).register(app.asApp());
    mockChatSession.mockReset();
    mockAnalyzeIntent.mockReset();
    mockAnalyzeIntent.mockResolvedValue({ intent: 'unknown', contexts: [] });
});

afterEach(() => { db.close(); });

describe('DM 메시지 처리', () => {
    test('question 응답 → say 호출, 세션 유지', async () => {
        mockChatSession.mockResolvedValueOnce({ type: 'question', text: '몇 개 구매했나요?' });

        const say = await app.triggerMessage('달걀 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith('몇 개 구매했나요?');
        expect(sessions.getMessages('U1')).toHaveLength(2);
    });

    test('result 응답 → executeCommand 실행, DB 행 생성, 세션 초기화', async () => {
        mockChatSession.mockResolvedValueOnce({
            type: 'result',
            text: '달걀 12개 구매 기록 완료',
            command: '/구매',
            params: '달걀 12개',
        });

        const say = await app.triggerMessage('달걀 12개 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('달걀'),
        }));

        const item = db.prepare('SELECT * FROM grocery_items WHERE name = ?').get('달걀') as { id: number };
        expect(item).toBeDefined();
        const purchase = db.prepare('SELECT qty FROM grocery_purchases WHERE item_id = ?').get(item.id) as { qty: number };
        expect(purchase.qty).toBe(12);

        expect(sessions.getMessages('U1')).toHaveLength(0);
    });

    test('unknown 응답 → 안내 텍스트 say 호출', async () => {
        mockChatSession.mockResolvedValueOnce({ type: 'unknown', text: '무슨 말인지 이해하지 못했어요.' });

        const say = await app.triggerMessage('아무말이나 해봄', 'U1', 'im');

        expect(say).toHaveBeenCalledWith('무슨 말인지 이해하지 못했어요.');
    });

    test('bot_id 있는 메시지 → say 미호출', async () => {
        const say = await app.triggerMessageWithOptions({
            text: '달걀 샀어',
            bot_id: 'B_BOT',
        });

        expect(say).not.toHaveBeenCalled();
        expect(mockChatSession).not.toHaveBeenCalled();
    });

    test('subtype 있는 메시지 → say 미호출', async () => {
        const say = await app.triggerMessageWithOptions({
            text: '달걀 샀어',
            subtype: 'message_changed',
        });

        expect(say).not.toHaveBeenCalled();
        expect(mockChatSession).not.toHaveBeenCalled();
    });

    test('analyzeIntent가 호출되고 context가 chatSession에 전달됨', async () => {
        mockAnalyzeIntent.mockResolvedValueOnce({
            intent: 'memo_search',
            contexts: [{ db: 'memos', type: 'recent' }],
        });
        mockChatSession.mockResolvedValueOnce({ type: 'unknown', text: '결과 없음' });

        await app.triggerMessage('최근 메모 보여줘', 'U1', 'im');

        expect(mockAnalyzeIntent).toHaveBeenCalledWith(expect.any(Array), '최근 메모 보여줘');
        expect(mockRetriever.retrieve).toHaveBeenCalledWith(
            [{ db: 'memos', type: 'recent' }],
            'U1',
        );
        expect(mockChatSession).toHaveBeenCalledWith(
            expect.any(Array),
            '최근 메모 보여줘',
            expect.any(Array),
        );
    });

    test('pipeline error → say 에러 메시지, 세션 미변경', async () => {
        mockAnalyzeIntent.mockRejectedValueOnce(new Error('API 오류'));

        const say = await app.triggerMessage('메모 보여줘', 'U1', 'im');

        expect(say).toHaveBeenCalledWith(expect.stringContaining('오류'));
        expect(sessions.getMessages('U1')).toHaveLength(0);
    });
});

describe('채널 멘션 처리', () => {
    test('채널에서 @봇 멘션 → 처리되고 thread_ts로 답장', async () => {
        mockChatSession.mockResolvedValueOnce({ type: 'question', text: '몇 개인가요?' });

        const say = await app.triggerMessageWithOptions({
            text: '<@U_BOT> 달걀 샀어',
            channelType: 'channel',
            botUserId: 'U_BOT',
            ts: '1111.2222',
        });

        expect(say).toHaveBeenCalledWith(expect.objectContaining({
            text: '몇 개인가요?',
            thread_ts: '1111.2222',
        }));
    });

    test('채널에서 멘션 없음 → say 미호출', async () => {
        const say = await app.triggerMessage('달걀 샀어', 'U1', 'channel');

        expect(say).not.toHaveBeenCalled();
        expect(mockChatSession).not.toHaveBeenCalled();
    });

    test('채널 result → thread_ts로 결과 답장', async () => {
        mockChatSession.mockResolvedValueOnce({
            type: 'result',
            text: '달걀 12개 구매 기록',
            command: '/구매',
            params: '달걀 12개',
        });

        const say = await app.triggerMessageWithOptions({
            text: '<@U_BOT> 달걀 12개 샀어',
            channelType: 'channel',
            botUserId: 'U_BOT',
            ts: '9999.0000',
        });

        expect(say).toHaveBeenCalledWith(expect.objectContaining({
            thread_ts: '9999.0000',
        }));
    });
});

describe('세션 관리', () => {
    test('result 후 세션 초기화 → 다음 메시지가 빈 이력으로 시작', async () => {
        mockChatSession
            .mockResolvedValueOnce({ type: 'result', text: '완료', command: '/할일', params: '장보기' })
            .mockResolvedValueOnce({ type: 'question', text: '뭘 도와드릴까요?' });

        await app.triggerMessage('장보기 추가해줘', 'U1', 'im');
        await app.triggerMessage('메모 보여줘', 'U1', 'im');

        const secondCallHistory = mockChatSession.mock.calls[1]?.[0] as unknown[];
        expect(secondCallHistory).toHaveLength(0);
    });

    test('question 후 세션 유지 → 다음 메시지가 이전 이력 포함', async () => {
        mockChatSession
            .mockResolvedValueOnce({ type: 'question', text: '몇 개인가요?' })
            .mockResolvedValueOnce({ type: 'result', text: '완료', command: '/구매', params: '달걀 12개' });

        await app.triggerMessage('달걀 샀어', 'U1', 'im');
        await app.triggerMessage('12개', 'U1', 'im');

        const secondCallHistory = mockChatSession.mock.calls[1]?.[0] as unknown[];
        expect(secondCallHistory).toHaveLength(2);
    });
});

describe('executeCommand — 할일', () => {
    test('/할일 → todos 테이블에 행 생성', async () => {
        mockChatSession.mockResolvedValueOnce({
            type: 'result',
            text: '할 일 추가',
            command: '/할일',
            params: '장보기',
        });

        await app.triggerMessage('장보기 추가해', 'U1', 'im');

        const row = db.prepare('SELECT * FROM todos WHERE content = ?').get('장보기') as { content: string } | undefined;
        expect(row).toBeDefined();
    });

    test('/완료 → 해당 할일 완료 처리', async () => {
        db.prepare("INSERT INTO todos (user_id, is_shared, content) VALUES ('U1', 0, '장보기')").run();

        mockChatSession.mockResolvedValueOnce({
            type: 'result',
            text: '완료 처리',
            command: '/완료',
            params: '장보기',
        });

        const say = await app.triggerMessage('장보기 완료했어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('완료'),
        }));

        const row = db.prepare('SELECT is_done FROM todos WHERE content = ?').get('장보기') as { is_done: number };
        expect(row.is_done).toBe(1);
    });
});
