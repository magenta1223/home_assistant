import Anthropic from '@anthropic-ai/sdk';
import type { ConversationMessage, ChatResponse, IntentAnalysis } from '../types';

const client = new Anthropic({ apiKey: process.env['ANTHROPIC_API_KEY'] });

const DATE_SYSTEM = `You are a date parser for a Korean home assistant bot.
Convert Korean date/time expressions to ISO 8601 format using today's date.
Return ONLY valid JSON, no explanation.

Examples:
- "내일 오후 3시" → { "date": "2026-02-28T15:00" }
- "이번 주 금요일" → { "date": "2026-02-27T00:00" }
- "다음 달 5일 오전 10시" → { "date": "2026-03-05T10:00" }

If no time given, use T00:00.`;

const DATE_RANGE_SYSTEM = `You are a date range parser for a Korean home assistant bot.
Convert Korean date range expressions to ISO 8601 using today's date.
Return ONLY valid JSON with "from" and "to" fields (YYYY-MM-DD format), no explanation.

Examples:
- "이번 주" → { "from": "2026-02-23", "to": "2026-03-01" }
- "다음 달" → { "from": "2026-03-01", "to": "2026-03-31" }
- "오늘" → { "from": "2026-02-27", "to": "2026-02-27" }`;

export async function parseDate(text: string): Promise<string | null> {
    const today = new Date().toISOString().slice(0, 10);
    const system = DATE_SYSTEM.replace(/\d{4}-\d{2}-\d{2}/g, today);
    const response = await client.messages.create({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 128,
        temperature: 0,
        system,
        messages: [{ role: 'user', content: text }],
    });
    const block = response.content[0];
    if (!block || block.type !== 'text') return null;
    const parsed = JSON.parse(block.text.trim()) as { date?: string };
    return parsed.date ?? null;
}

export async function parseDateRange(text: string): Promise<{ from: string | null; to: string | null }> {
    const today = new Date().toISOString().slice(0, 10);
    const system = DATE_RANGE_SYSTEM.replace(/\d{4}-\d{2}-\d{2}/g, today);
    const response = await client.messages.create({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 128,
        temperature: 0,
        system,
        messages: [{ role: 'user', content: text }],
    });
    const block = response.content[0];
    if (!block || block.type !== 'text') return { from: null, to: null };
    const parsed = JSON.parse(block.text.trim()) as { from?: string; to?: string };
    return { from: parsed.from ?? null, to: parsed.to ?? null };
}

const CHATBOT_SYSTEM = `당신은 한국 가정용 Slack 봇 어시스턴트입니다.
사용자의 자연어 메시지를 분석하여 아래 명령어 중 하나로 매핑하고 JSON으로 응답하세요.

사용 가능한 명령어:
- /메모 <내용> : 메모 저장 (앞에 "공유" 붙이면 가족 공유)
- /메모목록 [공유] : 메모 목록 조회
- /메모검색 <검색어> : 메모 검색
- /일정 <날짜+제목> : 일정 등록 (앞에 "공유" 붙이면 가족 공유)
- /일정목록 [기간] : 일정 목록 조회
- /상태 <기기> <상태> : 집 기기 상태 업데이트
- /상태확인 [기기] : 기기 상태 조회 (비우면 전체)
- /위치저장 <물건> <위치> : 물건 위치 저장
- /위치 <물건> : 물건 위치 조회
- /자산 <카테고리> <금액> : 자산 기록
- /자산확인 : 카테고리별 현재 자산
- /자산내역 [카테고리] : 자산 변동 내역
- /할일 <내용> : 할 일 추가 (앞에 "공유" 붙이면 가족 공유)
- /할일목록 [공유|완료] : 할 일 목록
- /완료 <키워드> : 할 일 완료 처리
- /레시피저장 <이름\n재료: ...\n순서: ...> : 레시피 저장
- /레시피 <검색어> : 레시피 검색
- /레시피목록 : 전체 레시피 목록
- /구매 <식재료> <수량+단위> : 구매 기록
- /재고 : 재고 현황 및 부족 예측

응답 형식 (반드시 유효한 JSON만 출력):
- 추가 정보가 필요한 경우: {"type":"question","text":"질문 내용"}
- 명령어 확정된 경우: {"type":"result","text":"안내 메시지","command":"/명령어","params":"파라미터"}
- 이해 불가한 경우: {"type":"unknown","text":"안내 메시지"}

규칙:
- params는 명령어 뒤에 오는 텍스트만 포함 (명령어 자체 제외)
- /구매의 params 형식: "재료이름 수량단위" (예: "달걀 12개")
- 항상 JSON만 응답하고 다른 텍스트는 포함하지 마세요`;

export async function chatSession(
    history: ConversationMessage[],
    userMessage: string,
): Promise<ChatResponse> {
    const messages: Array<{ role: 'user' | 'assistant'; content: string }> = [
        ...history.map(m => ({ role: m.role, content: m.content })),
        { role: 'user', content: userMessage },
    ];

    const response = await client.messages.create({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 512,
        system: CHATBOT_SYSTEM,
        messages,
    });

    const block = response.content[0];
    if (!block || block.type !== 'text') {
        return { type: 'unknown', text: '응답을 처리하지 못했어요.' };
    }

    try {
        return JSON.parse(block.text.trim()) as ChatResponse;
    } catch {
        return { type: 'unknown', text: '응답을 처리하지 못했어요.' };
    }
}

const INTENT_SYSTEM = `당신은 한국 가정용 Slack 봇의 의도 분석기입니다.
사용자 발화를 분석하여 필요한 DB context를 JSON으로 반환하세요.

사용 가능한 DB: memos, todos, schedules, home_status, item_locations, assets, recipes, grocery_items

조회 타입:
- recent: 최근 데이터가 필요할 때
- similar: 특정 내용과 유사한 데이터 검색 (searchText 필드 필수)
- query: 날짜/카테고리/키워드 등 조건 기반 조회 (filter 필드 필수)
  filter 가능 필드: keyword, dateFrom, dateTo, category, isShared

반환 형식 (JSON only):
{ "intent": "...", "contexts": [{ "db": "...", "type": "...", ...옵션 }] }

context가 불필요하면: { "intent": "...", "contexts": [] }
항상 JSON만 반환하세요.`;

export async function analyzeIntent(
    history: ConversationMessage[],
    userText: string,
): Promise<IntentAnalysis> {
    const messages: Array<{ role: 'user' | 'assistant'; content: string }> = [
        ...history.map(m => ({ role: m.role, content: m.content })),
        { role: 'user', content: userText },
    ];

    try {
        const response = await client.messages.create({
            model: 'claude-haiku-4-5-20251001',
            max_tokens: 256,
            temperature: 0,
            system: INTENT_SYSTEM,
            messages,
        });

        const block = response.content[0];
        if (!block || block.type !== 'text') return { intent: 'unknown', contexts: [] };

        const parsed = JSON.parse(block.text.trim()) as IntentAnalysis;
        return { intent: parsed.intent ?? 'unknown', contexts: parsed.contexts ?? [] };
    } catch {
        return { intent: 'unknown', contexts: [] };
    }
}
