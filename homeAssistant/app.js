const { App } = require('@slack/bolt');
require('dotenv').config();

const db = require('./db/init');

const app = new App({
    token: process.env.SLACK_BOT_TOKEN,
    signingSecret: process.env.SLACK_SIGNING_SECRET,
    appToken: process.env.SLACK_APP_TOKEN,
    socketMode: true,
});

// 커맨드 모듈 등록
const modules = [
    require('./commands/todo'),
    require('./commands/homeStatus'),
    require('./commands/itemLocation'),
    require('./commands/memo'),
    require('./commands/schedule'),
    require('./commands/recipe'),
    require('./commands/asset'),
    require('./commands/grocery'),
    require('./commands/help'),
];

modules.forEach(mod => mod.register(app, db));

(async () => {
    await app.start();
    console.log('⚡️ 홈 어시스턴트 봇이 소켓 모드에서 실행 중입니다!');
})();
