jest.mock('@anthropic-ai/sdk', () => {
    const mockCreate = jest.fn();
    const MockAnthropic = jest.fn().mockImplementation(() => ({
        messages: { create: mockCreate },
    }));
    return { __esModule: true, default: MockAnthropic };
});

import Anthropic from '@anthropic-ai/sdk';
import { analyzeIntent } from '../../src/nlp/claudeClient';

let mockCreate: jest.Mock;

beforeEach(() => {
    // Access mock inside beforeEach to ensure it's always fresh
    mockCreate = (Anthropic as unknown as jest.Mock).mock.results[0]?.value.messages.create as jest.Mock;
    mockCreate.mockReset();
});

test('returns IntentAnalysis with intent and contexts', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({
                intent: 'memo_search',
                contexts: [{ db: 'memos', type: 'similar', searchText: '치과' }],
            }),
        }],
    });

    const result = await analyzeIntent([], '치과 예약 메모 찾아줘');
    expect(result.intent).toBe('memo_search');
    expect(result.contexts).toHaveLength(1);
    expect(result.contexts[0]!.db).toBe('memos');
    expect(result.contexts[0]!.type).toBe('similar');
});

test('returns empty contexts when no DB needed', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({ intent: 'greeting', contexts: [] }),
        }],
    });

    const result = await analyzeIntent([], '안녕');
    expect(result.intent).toBe('greeting');
    expect(result.contexts).toHaveLength(0);
});

test('returns fallback on JSON parse error', async () => {
    mockCreate.mockResolvedValue({
        content: [{ type: 'text', text: 'invalid json' }],
    });

    const result = await analyzeIntent([], '아무말');
    expect(result.intent).toBe('unknown');
    expect(result.contexts).toHaveLength(0);
});

test('returns fallback on API rejection (network error)', async () => {
    mockCreate.mockRejectedValue(new Error('network error'));

    const result = await analyzeIntent([], '테스트');
    expect(result.intent).toBe('unknown');
    expect(result.contexts).toHaveLength(0);
});

test('returns fallback when content block is not text type', async () => {
    mockCreate.mockResolvedValue({
        content: [{ type: 'tool_use', id: 'x', name: 'y', input: {} }],
    });

    const result = await analyzeIntent([], '테스트');
    expect(result.intent).toBe('unknown');
    expect(result.contexts).toHaveLength(0);
});

test('filters out contexts with invalid db names', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({
                intent: 'memo_search',
                contexts: [
                    { db: 'memos', type: 'recent' },
                    { db: 'invalid_db', type: 'recent' },
                ],
            }),
        }],
    });

    const result = await analyzeIntent([], '메모 보여줘');
    expect(result.contexts).toHaveLength(1);
    expect(result.contexts[0]!.db).toBe('memos');
});

test('query type with filter is returned correctly', async () => {
    mockCreate.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({
                intent: 'schedule_list',
                contexts: [{ db: 'schedules', type: 'query', filter: { dateFrom: '2026-03-01', dateTo: '2026-03-31' } }],
            }),
        }],
    });

    const result = await analyzeIntent([], '이번달 일정');
    expect(result.intent).toBe('schedule_list');
    expect(result.contexts[0]!.type).toBe('query');
    expect(result.contexts[0]!.filter?.dateFrom).toBe('2026-03-01');
});

test('history is forwarded to the API messages array', async () => {
    mockCreate.mockResolvedValue({
        content: [{ type: 'text', text: JSON.stringify({ intent: 'other', contexts: [] }) }],
    });

    await analyzeIntent(
        [{ role: 'user', content: '이전 메시지' }, { role: 'assistant', content: '이전 응답' }],
        '새 메시지',
    );

    const callArgs = mockCreate.mock.calls[0]?.[0] as { messages: Array<{ role: string; content: string }> };
    expect(callArgs.messages).toHaveLength(3);
    expect(callArgs.messages[0]!.content).toBe('이전 메시지');
    expect(callArgs.messages[2]!.content).toBe('새 메시지');
});
