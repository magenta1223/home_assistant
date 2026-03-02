import { App } from "@slack/bolt";
import "dotenv/config";
import db from "./db/init";
import { ChatbotCommand } from "./commands/ChatbotCommand";
import { ChatbotHttpCommand } from "./commands/ChatbotHttpCommand";

const app = new App({
    token: process.env["SLACK_BOT_TOKEN"]!,
    signingSecret: process.env["SLACK_SIGNING_SECRET"]!,
    appToken: process.env["SLACK_APP_TOKEN"]!,
    socketMode: true,
});

// Use HTTP adapter when BACKEND_URL is set (Kotlin backend running),
// otherwise fall back to the legacy local TypeScript implementation.
if (process.env["BACKEND_URL"]) {
    new ChatbotHttpCommand(db).register(app);
} else {
    new ChatbotCommand(db).register(app);
}

void (async () => {
    await app.start();
    console.log("⚡️ 홈 어시스턴트 봇이 소켓 모드에서 실행 중입니다!");
})();
