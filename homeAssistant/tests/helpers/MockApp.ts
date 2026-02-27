import type { App } from '@slack/bolt';

type CommandHandler = (ctx: {
    command: { text: string; user_id: string };
    ack: jest.Mock;
    respond: jest.Mock;
}) => Promise<void>;

export class MockApp {
    private readonly handlers = new Map<string, CommandHandler>();

    command(name: string, handler: CommandHandler): void {
        this.handlers.set(name, handler);
    }

    async trigger(name: string, text: string, userId = 'U_TEST'): Promise<jest.Mock> {
        const respond = jest.fn();
        const ack = jest.fn().mockResolvedValue(undefined);
        const handler = this.handlers.get(name);
        if (!handler) throw new Error(`No handler registered for command: ${name}`);
        await handler({ command: { text, user_id: userId }, ack, respond });
        return respond;
    }

    asApp(): App {
        return this as unknown as App;
    }
}
