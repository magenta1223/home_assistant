import { App } from '@slack/bolt';
import 'dotenv/config';
import db from './db/init';
import { TodoCommand } from './commands/TodoCommand';
import { HomeStatusCommand } from './commands/HomeStatusCommand';
import { ItemLocationCommand } from './commands/ItemLocationCommand';
import { MemoCommand } from './commands/MemoCommand';
import { ScheduleCommand } from './commands/ScheduleCommand';
import { RecipeCommand } from './commands/RecipeCommand';
import { AssetCommand } from './commands/AssetCommand';
import { GroceryCommand } from './commands/GroceryCommand';
import { HelpCommand } from './commands/HelpCommand';

const app = new App({
    token: process.env['SLACK_BOT_TOKEN']!,
    signingSecret: process.env['SLACK_SIGNING_SECRET']!,
    appToken: process.env['SLACK_APP_TOKEN']!,
    socketMode: true,
});

const commands = [
    new TodoCommand(db),
    new HomeStatusCommand(db),
    new ItemLocationCommand(db),
    new MemoCommand(db),
    new ScheduleCommand(db),
    new RecipeCommand(db),
    new AssetCommand(db),
    new GroceryCommand(db),
    new HelpCommand(db),
];

commands.forEach(cmd => cmd.register(app));

void (async () => {
    await app.start();
    console.log('⚡️ 홈 어시스턴트 봇이 소켓 모드에서 실행 중입니다!');
})();
