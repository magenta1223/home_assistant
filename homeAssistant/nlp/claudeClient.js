const Anthropic = require('@anthropic-ai/sdk');

const client = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });

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

async function parseDate(text) {
    const today = new Date().toISOString().slice(0, 10);
    const system = DATE_SYSTEM.replace(/\d{4}-\d{2}-\d{2}/g, today);
    const response = await client.messages.create({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 128,
        temperature: 0,
        system,
        messages: [{ role: 'user', content: text }],
    });
    const raw = response.content[0].text.trim();
    const parsed = JSON.parse(raw);
    return parsed.date ?? null;
}

async function parseDateRange(text) {
    const today = new Date().toISOString().slice(0, 10);
    const system = DATE_RANGE_SYSTEM.replace(/\d{4}-\d{2}-\d{2}/g, today);
    const response = await client.messages.create({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 128,
        temperature: 0,
        system,
        messages: [{ role: 'user', content: text }],
    });
    const raw = response.content[0].text.trim();
    const parsed = JSON.parse(raw);
    return { from: parsed.from ?? null, to: parsed.to ?? null };
}

module.exports = { parseDate, parseDateRange };
