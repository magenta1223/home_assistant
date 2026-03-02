import type { ConversationMessage } from '../types';

interface Session {
    messages: ConversationMessage[];
    lastActivity: number;
}

export class SessionManager {
    private readonly sessions = new Map<string, Session>();

    constructor(private readonly timeoutMs = 10 * 60 * 1000) {}

    getMessages(userId: string): ConversationMessage[] {
        const session = this.sessions.get(userId);
        if (!session) return [];
        if (Date.now() - session.lastActivity > this.timeoutMs) {
            this.sessions.delete(userId);
            return [];
        }
        return [...session.messages];
    }

    addMessage(userId: string, role: 'user' | 'assistant', content: string): void {
        const now = Date.now();
        const existing = this.sessions.get(userId);
        if (existing && now - existing.lastActivity <= this.timeoutMs) {
            existing.messages.push({ role, content });
            existing.lastActivity = now;
        } else {
            this.sessions.set(userId, {
                messages: [{ role, content }],
                lastActivity: now,
            });
        }
    }

    resetSession(userId: string): void {
        this.sessions.delete(userId);
    }
}
