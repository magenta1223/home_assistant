jest.mock('@anthropic-ai/sdk', () => {
    const mockCreate = jest.fn();
    const MockAnthropic = jest.fn().mockImplementation(() => ({
        messages: { create: mockCreate },
    }));
    return { __esModule: true, default: MockAnthropic };
});

import Anthropic from '@anthropic-ai/sdk';
import { analyzeIntent } from '../../src/nlp/claudeClient';

// Access the mock create function from the singleton instance
const mockInstance = (Anthropic as unknown as jest.Mock).mock.results[0]?.value as { messages: { create: jest.Mock } } | undefined;

beforeEach(() => {
    mockInstance?.messages.create.mockReset();
});

test('returns IntentAnalysis with intent and contexts', async () => {
    mockInstance?.messages.create.mockResolvedValue({
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
    mockInstance?.messages.create.mockResolvedValue({
        content: [{
            type: 'text',
            text: JSON.stringify({ intent: 'greeting', contexts: [] }),
        }],
    });

    const result = await analyzeIntent([], '안녕');
    expect(result.contexts).toHaveLength(0);
});

test('returns fallback on JSON parse error', async () => {
    mockInstance?.messages.create.mockResolvedValue({
        content: [{ type: 'text', text: 'invalid json' }],
    });

    const result = await analyzeIntent([], '아무말');
    expect(result.intent).toBe('unknown');
    expect(result.contexts).toHaveLength(0);
});
