import type { Database } from 'better-sqlite3';
import { createTestDb } from '../helpers/db';
import { MockApp } from '../helpers/MockApp';
import { ChatbotHttpCommand } from '../../src/commands/ChatbotHttpCommand';

// Mock global fetch
const mockFetch = jest.fn();
global.fetch = mockFetch;

function makeFetchOk(body: object) {
    return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(body),
    } as Response);
}

function makeFetchError(status = 500) {
    return Promise.resolve({ ok: false, status } as Response);
}

let db: Database;
let app: MockApp;

beforeEach(() => {
    db = createTestDb();
    app = new MockApp();
    new ChatbotHttpCommand(db).register(app.asApp());
    mockFetch.mockReset();
});

afterEach(() => { db.close(); });

describe('DM 메시지 처리 — HTTP 어댑터', () => {
    test('백엔드 question 응답 → say 호출', async () => {
        mockFetch.mockReturnValueOnce(makeFetchOk({ type: 'question', text: '몇 개 구매했나요?' }));

        const say = await app.triggerMessage('달걀 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith('몇 개 구매했나요?');
        expect(mockFetch).toHaveBeenCalledWith(
            expect.stringContaining('/api/chat'),
            expect.objectContaining({ method: 'POST' }),
        );
    });

    test('백엔드 result 응답 → say 호출', async () => {
        mockFetch.mockReturnValueOnce(makeFetchOk({
            type: 'result',
            text: '달걀 12개 구매 기록 완료!',
            sessionReset: true,
        }));

        const say = await app.triggerMessage('달걀 12개 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith('달걀 12개 구매 기록 완료!');
    });

    test('bot_id 있는 메시지 → fetch 미호출', async () => {
        const say = await app.triggerMessageWithOptions({
            text: '달걀 샀어',
            bot_id: 'B_BOT',
        });

        expect(say).not.toHaveBeenCalled();
        expect(mockFetch).not.toHaveBeenCalled();
    });

    test('subtype 있는 메시지 → fetch 미호출', async () => {
        const say = await app.triggerMessageWithOptions({
            text: '달걀 샀어',
            subtype: 'message_changed',
        });

        expect(say).not.toHaveBeenCalled();
        expect(mockFetch).not.toHaveBeenCalled();
    });

    test('백엔드 오류 → 에러 메시지 say 호출', async () => {
        mockFetch.mockReturnValueOnce(makeFetchError(500));

        const say = await app.triggerMessage('달걀 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith(expect.stringContaining('오류'));
    });

    test('네트워크 실패 → 에러 메시지 say 호출', async () => {
        mockFetch.mockRejectedValueOnce(new Error('Network error'));

        const say = await app.triggerMessage('달걀 샀어', 'U1', 'im');

        expect(say).toHaveBeenCalledWith(expect.stringContaining('오류'));
    });
});

describe('채널 멘션 처리 — HTTP 어댑터', () => {
    test('채널에서 @봇 멘션 → thread_ts로 답장', async () => {
        mockFetch.mockReturnValueOnce(makeFetchOk({ type: 'question', text: '몇 개인가요?' }));

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

    test('채널에서 멘션 없음 → fetch 미호출', async () => {
        const say = await app.triggerMessage('달걀 샀어', 'U1', 'channel');

        expect(say).not.toHaveBeenCalled();
        expect(mockFetch).not.toHaveBeenCalled();
    });

    test('백엔드 오류 시 채널에서도 thread_ts로 에러 답장', async () => {
        mockFetch.mockReturnValueOnce(makeFetchError());

        const say = await app.triggerMessageWithOptions({
            text: '<@U_BOT> 달걀 샀어',
            channelType: 'channel',
            botUserId: 'U_BOT',
            ts: '9999.0000',
        });

        expect(say).toHaveBeenCalledWith(expect.objectContaining({
            text: expect.stringContaining('오류'),
            thread_ts: '9999.0000',
        }));
    });
});

describe('fetch 페이로드 검증', () => {
    test('DM 메시지 → 올바른 JSON 페이로드 전송', async () => {
        mockFetch.mockReturnValueOnce(makeFetchOk({ type: 'result', text: '완료' }));

        await app.triggerMessage('달걀 샀어', 'U1', 'im');

        const [, options] = mockFetch.mock.calls[0]!;
        const body = JSON.parse((options as RequestInit).body as string) as {
            platform: string; conversationId: string; userId: string; text: string;
        };
        expect(body.platform).toBe('slack');
        expect(body.userId).toBe('U1');
        expect(body.text).toBe('달걀 샀어');
    });

    test('채널 멘션 → 멘션 제거된 텍스트 전송', async () => {
        mockFetch.mockReturnValueOnce(makeFetchOk({ type: 'result', text: '완료' }));

        await app.triggerMessageWithOptions({
            text: '<@U_BOT> 달걀 12개 샀어',
            channelType: 'channel',
            botUserId: 'U_BOT',
            ts: '1111.0000',
        });

        const [, options] = mockFetch.mock.calls[0]!;
        const body = JSON.parse((options as RequestInit).body as string) as { text: string };
        expect(body.text).toBe('달걀 12개 샀어');
    });
});
