import type { App } from '@slack/bolt';
import { BaseCommand } from '../core/BaseCommand';
import type { Database } from 'better-sqlite3';

const BACKEND_URL = process.env['BACKEND_URL'] ?? 'http://localhost:8080';

interface BackendResponse {
    type: string;
    text: string;
    sessionReset?: boolean;
}

/**
 * Thin Slack adapter for the Kotlin backend.
 * All NLP/DB/session logic lives in the Kotlin backend.
 * This class handles only Slack protocol concerns:
 * - DM vs channel mention detection
 * - Thread reply routing
 * - Bot/subtype filtering
 */
export class ChatbotHttpCommand extends BaseCommand {
    constructor(db: Database) {
        super(db);
    }

    register(app: App): void {
        app.message(async ({ message, say, context }) => {
            const msg = message as {
                text?: string;
                user?: string;
                channel?: string;
                channel_type?: string;
                ts?: string;
                bot_id?: string;
                subtype?: string;
                thread_ts?: string;
            };

            if (msg.bot_id || msg.subtype) return;

            const text = msg.text ?? '';
            const userId = msg.user ?? '';
            const channelId = msg.channel ?? userId;
            const channelType = msg.channel_type ?? '';
            const ts = msg.ts ?? '';
            const botUserId = (context as { botUserId?: string }).botUserId ?? '';

            let userText: string;
            let threadTs: string | undefined;

            if (channelType === 'im') {
                userText = text.trim();
            } else {
                const mention = `<@${botUserId}>`;
                if (!text.includes(mention)) return;
                userText = text.replace(new RegExp(`<@${botUserId}>`, 'g'), '').trim();
                threadTs = msg.thread_ts ?? ts;
            }

            if (!userText) return;

            try {
                const res = await fetch(`${BACKEND_URL}/api/chat`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        platform: 'slack',
                        conversationId: channelId,
                        userId,
                        text: userText,
                        metadata: { channelType },
                    }),
                });

                if (!res.ok) {
                    throw new Error(`Backend returned ${res.status}`);
                }

                const response = await res.json() as BackendResponse;
                const replyText = response.text;

                if (threadTs) {
                    await say({ text: replyText, thread_ts: threadTs } as never);
                } else {
                    await say(replyText);
                }
            } catch {
                const errMsg = '죄송해요, 요청을 처리하는 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.';
                if (threadTs) {
                    await say({ text: errMsg, thread_ts: threadTs } as never);
                } else {
                    await say(errMsg);
                }
            }
        });
    }
}
