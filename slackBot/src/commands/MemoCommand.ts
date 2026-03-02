import type { App } from "@slack/bolt";
import type { SlackResponse, MemoRow } from "../types";
import { SharedableCommand } from "../core/SharedableCommand";
import { listToBlocks } from "../formatters/blocks";

export class MemoCommand extends SharedableCommand {
    register(app: App): void {
        app.command("/메모", async ({ command, ack, respond }) => {
            await ack();
            await respond(
                this.addMemo(command.text.trim(), command.user_id) as never,
            );
        });

        app.command("/메모목록", async ({ command, ack, respond }) => {
            await ack();
            await respond(
                this.listMemos(command.text.trim(), command.user_id) as never,
            );
        });

        app.command("/메모검색", async ({ command, ack, respond }) => {
            await ack();
            await respond(
                this.searchMemos(command.text.trim(), command.user_id) as never,
            );
        });
    }

    private addMemo(text: string, userId: string): SlackResponse {
        if (!text)
            return this.err(
                "내용을 입력해주세요. 예: `/메모 치과 예약 내일 3시`",
            );

        const { isShared, content } = this.parseShared(text);
        this.db
            .prepare(
                "INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)",
            )
            .run(userId, isShared ? 1 : 0, content);

        return {
            text: isShared
                ? `공유 메모 저장했어요: _${content}_`
                : "메모 저장했어요!",
            response_type: isShared ? "in_channel" : "ephemeral",
        };
    }

    private listMemos(filter: string, userId: string): SlackResponse {
        let rows: MemoRow[];
        if (filter === "공유") {
            rows = this.db
                .prepare(
                    "SELECT * FROM memos WHERE is_shared = 1 ORDER BY created_at DESC LIMIT 15",
                )
                .all() as MemoRow[];
        } else {
            rows = this.db
                .prepare(
                    "SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) ORDER BY created_at DESC LIMIT 15",
                )
                .all(userId) as MemoRow[];
        }

        const label = filter === "공유" ? "공유 메모" : "내 메모";
        return {
            blocks: listToBlocks(label, rows, (m) => {
                const shared = m.is_shared ? " _(공유)_" : "";
                const title = m.title ? `*${m.title}*\n` : "";
                return `${title}${m.content}${shared}\n_${m.created_at}_`;
            }),
            response_type: "ephemeral",
        };
    }

    private searchMemos(query: string, userId: string): SlackResponse {
        if (!query)
            return this.err("검색어를 입력해주세요. 예: `/메모검색 치과`");

        const rows = this.db
            .prepare(
                `
            SELECT * FROM memos
            WHERE (user_id = ? OR is_shared = 1)
              AND (title LIKE ? OR content LIKE ? OR tags LIKE ?)
            ORDER BY created_at DESC LIMIT 10
        `,
            )
            .all(userId, `%${query}%`, `%${query}%`, `%${query}%`) as MemoRow[];

        return {
            blocks: listToBlocks(`"${query}" 검색 결과`, rows, (m) => {
                const shared = m.is_shared ? " _(공유)_" : "";
                return `${m.content}${shared}\n_${m.created_at}_`;
            }),
            response_type: "ephemeral",
        };
    }
}
