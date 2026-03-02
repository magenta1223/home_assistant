import type { App } from '@slack/bolt';

export interface ICommand {
    register(app: App): void;
}
