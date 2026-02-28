import { App } from "@slack/bolt";
import "dotenv/config";
import db from "./db/init";
import { ChatbotCommand } from "./commands/ChatbotCommand";

const app = new App({
    token: process.env["SLACK_BOT_TOKEN"]!,
    signingSecret: process.env["SLACK_SIGNING_SECRET"]!,
    appToken: process.env["SLACK_APP_TOKEN"]!,
    socketMode: true,
});

new ChatbotCommand(db).register(app);

void (async () => {
    await app.start();
    console.log("⚡️ 홈 어시스턴트 봇이 소켓 모드에서 실행 중입니다!");
})();
