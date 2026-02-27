const { App } = require("@slack/bolt");

require("dotenv").config();

const app = new App({
    token: process.env.SLACK_BOT_TOKEN,
    signingSecret: process.env.SLACK_SIGNING_SECRET,
    appToken: process.env.SLACK_APP_TOKEN,
    socketMode: true,
});

// `/test` 커맨드에 대한 핸들러
//
app.command("/test", async ({ command, ack, respond }) => {
    console.log("test command received");

    // 1. 슬랙에 요청을 받았음을 즉시 알림
    await ack();

    // 2. 메시지를 보낼 채널에 응답
    await respond({
        text: "test입니다",
        response_type: "in_channel",
    });
});
app.message(async ({ message, client }) => {
    console.log("messageRecieved: " + message.text);

    try {
        if (
            message.subtype === "bot_message" ||
            message.subtype === "message_changed"
        ) {
            return;
        }

        const mentionedUsers = message.text.match(/<@([A-Z0-9]+)>/g);

        let mentionedUserString = "";
        mentionedUsers.forEach((userTag) => {
            mentionedUserString += ", " + userTag;
        });
        console.log("mentioned Users: " + mentionedUserString);

        if (mentionedUsers) {
            const tokenEmojiRegExp = /(:coin:|:token_icon:)/g;
            const tokenEmojiCount = (message.text.match(tokenEmojiRegExp) || [])
                .length;

            console.log("tokenCount: " + tokenEmojiCount);

            if (tokenEmojiCount > 0) {
                mentionedUsers.forEach(async (userTag) => {
                    const userId = userTag.replace(/<@|>/g, "");

                    // 멘션된 사람에게 토큰 지급 메시지 DM 발송
                    await client.chat.postMessage({
                        channel: userId, // <-- 이 부분은 DM 채널 ID를 의미
                        text: `안녕하세요! ${message.user}님이 당신을 멘션하며 :coin: 토큰 ${tokenEmojiCount}개를 지급했습니다!`,
                    });
                });
            }
        }
    } catch (error) {
        console.error(error);
    }
});

(async () => {
    await app.start();
    console.log("⚡️ 봇이 소켓 모드에서 실행 중입니다!");
})();
