import type { App } from '@slack/bolt';

type CommandHandler = (ctx: {
    command: { text: string; user_id: string };
    ack: jest.Mock;
    respond: jest.Mock;
}) => Promise<void>;

type MessageHandler = (ctx: {
    message: {
        text: string;
        user: string;
        channel_type: string;
        ts: string;
        bot_id?: string;
        subtype?: string;
        thread_ts?: string;
    };
    say: jest.Mock;
    context: { botUserId: string };
}) => Promise<void>;

export class MockApp {
    private readonly handlers = new Map<string, CommandHandler>();
    private messageHandler: MessageHandler | null = null;

    command(name: string, handler: CommandHandler): void {
        this.handlers.set(name, handler);
    }

    message(handler: MessageHandler): void {
        this.messageHandler = handler;
    }

    async trigger(name: string, text: string, userId = 'U_TEST'): Promise<jest.Mock> {
        const respond = jest.fn();
        const ack = jest.fn().mockResolvedValue(undefined);
        const handler = this.handlers.get(name);
        if (!handler) throw new Error(`No handler registered for command: ${name}`);
        await handler({ command: { text, user_id: userId }, ack, respond });
        return respond;
    }

    async triggerMessage(
        text: string,
        userId = 'U_TEST',
        channelType = 'im',
        botUserId = 'U_BOT',
    ): Promise<jest.Mock> {
        if (!this.messageHandler) throw new Error('No message handler registered');
        const say = jest.fn();
        await this.messageHandler({
            message: {
                text,
                user: userId,
                channel_type: channelType,
                ts: '1000000.000000',
            },
            say,
            context: { botUserId },
        });
        return say;
    }

    async triggerMessageWithOptions(options: {
        text: string;
        userId?: string;
        channelType?: string;
        botUserId?: string;
        bot_id?: string;
        subtype?: string;
        thread_ts?: string;
        ts?: string;
    }): Promise<jest.Mock> {
        if (!this.messageHandler) throw new Error('No message handler registered');
        const say = jest.fn();
        const msg: {
            text: string;
            user: string;
            channel_type: string;
            ts: string;
            bot_id?: string;
            subtype?: string;
            thread_ts?: string;
        } = {
            text: options.text,
            user: options.userId ?? 'U_TEST',
            channel_type: options.channelType ?? 'im',
            ts: options.ts ?? '1000000.000000',
        };
        if (options.bot_id !== undefined) msg.bot_id = options.bot_id;
        if (options.subtype !== undefined) msg.subtype = options.subtype;
        if (options.thread_ts !== undefined) msg.thread_ts = options.thread_ts;
        await this.messageHandler({ message: msg, say, context: { botUserId: options.botUserId ?? 'U_BOT' } });
        return say;
    }

    asApp(): App {
        return this as unknown as App;
    }
}
