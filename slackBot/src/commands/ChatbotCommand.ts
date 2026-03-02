import type { App } from '@slack/bolt';
import type { KnownBlock } from '@slack/types';
import type { Database } from 'better-sqlite3';
import type {
    GroceryItemRow,
    GroceryPurchaseRow,
    HomeStatusRow,
    ItemLocationRow,
    MemoRow,
    RecipeRow,
    ScheduleRow,
    TodoRow,
    AssetRow,
} from '../types';
import { BaseCommand } from '../core/BaseCommand';
import { SessionManager } from '../session/SessionManager';
import { ContextRetriever } from '../session/ContextRetriever';
import { EmbeddingService } from '../session/EmbeddingService';
import { chatSession, parseDate, parseDateRange, analyzeIntent } from '../nlp/claudeClient';
import { headerBlock, sectionBlock, divider, listToBlocks } from '../formatters/blocks';

const QTY_REGEX = /^(.+?)\s+(\d+(?:\.\d+)?)\s*(개|L|리터|g|kg|봉|팩|병|캔|줄|판|묶음|포)$/;

interface CommandResult {
    text?: string;
    blocks?: KnownBlock[];
}

export class ChatbotCommand extends BaseCommand {
    private readonly sessions: SessionManager;
    private readonly contextRetriever: ContextRetriever;

    constructor(db: Database, sessionManager?: SessionManager, contextRetriever?: ContextRetriever) {
        super(db);
        this.sessions = sessionManager ?? new SessionManager();
        this.contextRetriever = contextRetriever ?? new ContextRetriever(db, new EmbeddingService(db));
    }

    register(app: App): void {
        app.message(async ({ message, say, context }) => {
            const msg = message as {
                text?: string;
                user?: string;
                channel_type?: string;
                ts?: string;
                bot_id?: string;
                subtype?: string;
                thread_ts?: string;
            };

            if (msg.bot_id || msg.subtype) return;

            const text = msg.text ?? '';
            const userId = msg.user ?? '';
            const channelType = msg.channel_type ?? '';
            const ts = msg.ts ?? '';
            const botUserId = (context as { botUserId?: string }).botUserId ?? '';

            let userText: string;
            let threadTs: string | undefined;

            if (channelType === 'im') {
                userText = text.trim();
            } else {
                const mention = `<@${botUserId}>`;
                if (!text.includes(mention)) return;
                userText = text.replace(new RegExp(`<@${botUserId}>`, 'g'), '').trim();
                threadTs = msg.thread_ts ?? ts;
            }

            if (!userText) return;

            const history = this.sessions.getMessages(userId);
            try {
                const intentAnalysis = await analyzeIntent(history, userText);
                const contextResults = await this.contextRetriever.retrieve(intentAnalysis.contexts, userId);
                const response = await chatSession(history, userText, contextResults);

                this.sessions.addMessage(userId, 'user', userText);
                this.sessions.addMessage(userId, 'assistant', response.text);

                if (response.type === 'result' && response.command) {
                    const result = await this.executeCommand(response.command, response.params ?? '', userId);
                    this.sessions.resetSession(userId);
                    const replyText = result.text ?? response.text;
                    if (threadTs) {
                        await say({ text: replyText, blocks: result.blocks, thread_ts: threadTs } as never);
                    } else {
                        await say({ text: replyText, blocks: result.blocks } as never);
                    }
                } else {
                    if (threadTs) {
                        await say({ text: response.text, thread_ts: threadTs } as never);
                    } else {
                        await say(response.text);
                    }
                }
            } catch {
                const errMsg = '죄송해요, 요청을 처리하는 중 오류가 발생했어요. 잠시 후 다시 시도해주세요.';
                if (threadTs) {
                    await say({ text: errMsg, thread_ts: threadTs } as never);
                } else {
                    await say(errMsg);
                }
            }
        });
    }

    private async executeCommand(name: string, params: string, userId: string): Promise<CommandResult> {
        switch (name) {
            case '/할일': {
                if (!params) return { text: '할 일 내용을 입력해주세요.' };
                const isShared = params.startsWith('공유 ');
                const content = isShared ? params.slice(3).trim() : params;
                this.db.prepare('INSERT INTO todos (user_id, is_shared, content) VALUES (?, ?, ?)').run(userId, isShared ? 1 : 0, content);
                const inserted = this.db.prepare('SELECT last_insert_rowid() as id').get() as { id: number };
                void this.contextRetriever.storeEmbedding('todos', inserted.id, content);
                return { text: isShared ? `공유 할 일 추가: *${content}*` : `할 일 추가: *${content}*` };
            }

            case '/할일목록': {
                let rows: TodoRow[];
                if (params === '공유') {
                    rows = this.db.prepare('SELECT * FROM todos WHERE is_shared = 1 AND is_done = 0 ORDER BY created_at DESC').all() as TodoRow[];
                } else if (params === '완료') {
                    rows = this.db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 1 ORDER BY done_at DESC LIMIT 20').all(userId) as TodoRow[];
                } else {
                    rows = this.db.prepare('SELECT * FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 ORDER BY created_at DESC').all(userId) as TodoRow[];
                }
                const label = params === '완료' ? '완료된 할 일' : (params === '공유' ? '공유 할 일' : '할 일 목록');
                return { blocks: listToBlocks(label, rows, t => `${t.content}${t.is_shared ? ' _(공유)_' : ''}`) };
            }

            case '/완료': {
                if (!params) return { text: '완료할 할 일을 입력해주세요.' };
                const row = this.db.prepare(
                    'SELECT id FROM todos WHERE (user_id = ? OR is_shared = 1) AND is_done = 0 AND content LIKE ? LIMIT 1'
                ).get(userId, `%${params}%`) as { id: number } | undefined;
                if (!row) return { text: `"${params}"에 해당하는 미완료 할 일을 찾지 못했어요.` };
                this.db.prepare("UPDATE todos SET is_done = 1, done_at = datetime('now','localtime') WHERE id = ?").run(row.id);
                return { text: '완료 처리했어요!' };
            }

            case '/메모': {
                if (!params) return { text: '메모 내용을 입력해주세요.' };
                const isShared = params.startsWith('공유 ');
                const content = isShared ? params.slice(3).trim() : params;
                this.db.prepare('INSERT INTO memos (user_id, is_shared, content) VALUES (?, ?, ?)').run(userId, isShared ? 1 : 0, content);
                return { text: isShared ? `공유 메모 저장했어요: _${content}_` : '메모 저장했어요!' };
            }

            case '/메모목록': {
                let rows: MemoRow[];
                if (params === '공유') {
                    rows = this.db.prepare('SELECT * FROM memos WHERE is_shared = 1 ORDER BY created_at DESC LIMIT 15').all() as MemoRow[];
                } else {
                    rows = this.db.prepare('SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) ORDER BY created_at DESC LIMIT 15').all(userId) as MemoRow[];
                }
                const label = params === '공유' ? '공유 메모' : '내 메모';
                return {
                    blocks: listToBlocks(label, rows, m => {
                        const shared = m.is_shared ? ' _(공유)_' : '';
                        const title = m.title ? `*${m.title}*\n` : '';
                        return `${title}${m.content}${shared}\n_${m.created_at}_`;
                    }),
                };
            }

            case '/메모검색': {
                if (!params) return { text: '검색어를 입력해주세요.' };
                const rows = this.db.prepare(
                    'SELECT * FROM memos WHERE (user_id = ? OR is_shared = 1) AND (title LIKE ? OR content LIKE ? OR tags LIKE ?) ORDER BY created_at DESC LIMIT 10'
                ).all(userId, `%${params}%`, `%${params}%`, `%${params}%`) as MemoRow[];
                return {
                    blocks: listToBlocks(`"${params}" 검색 결과`, rows, m => {
                        const shared = m.is_shared ? ' _(공유)_' : '';
                        return `${m.content}${shared}\n_${m.created_at}_`;
                    }),
                };
            }

            case '/일정': {
                if (!params) return { text: '일정을 입력해주세요. 예: "내일 오후 3시 치과"' };
                const isShared = params.startsWith('공유 ');
                const content = isShared ? params.slice(3).trim() : params;
                const eventDate = await parseDate(content);
                if (!eventDate) return { text: '날짜/시간 정보를 찾지 못했어요. 날짜를 포함해서 입력해주세요.' };
                this.db.prepare('INSERT INTO schedules (user_id, is_shared, title, event_date) VALUES (?, ?, ?, ?)').run(userId, isShared ? 1 : 0, content, eventDate);
                return {
                    text: isShared
                        ? `공유 일정 등록: *${content}* (${eventDate})`
                        : `일정 등록: *${content}* (${eventDate})`,
                };
            }

            case '/일정목록': {
                let from: string | null;
                let to: string | null;
                if (params) {
                    ({ from, to } = await parseDateRange(params));
                } else {
                    const today = new Date();
                    from = today.toISOString().slice(0, 10);
                    const future = new Date(today);
                    future.setDate(future.getDate() + 30);
                    to = future.toISOString().slice(0, 10);
                }
                const rows = this.db.prepare(
                    'SELECT * FROM schedules WHERE (user_id = ? OR is_shared = 1) AND date(event_date) BETWEEN ? AND ? ORDER BY event_date ASC'
                ).all(userId, from, to) as ScheduleRow[];
                const label = params ? `일정 (${params})` : '일정 (다음 30일)';
                return {
                    blocks: listToBlocks(label, rows, s => {
                        const shared = s.is_shared ? ' _(공유)_' : '';
                        return `*${s.title}*${shared}\n_${s.event_date}_`;
                    }),
                };
            }

            case '/상태': {
                if (!params) return { text: '기기명과 상태를 입력해주세요. 예: "에어컨 켜"' };
                const parts = params.trim().split(/\s+/);
                if (parts.length < 2) return { text: '상태도 함께 입력해주세요. 예: "에어컨 켜"' };
                const device = parts[0]!;
                const status = parts.slice(1).join(' ');
                this.db.prepare(
                    `INSERT INTO home_status (device_name, status, set_by)
                     VALUES (?, ?, ?)
                     ON CONFLICT(device_name) DO UPDATE SET
                       status = excluded.status,
                       set_by = excluded.set_by,
                       updated_at = datetime('now','localtime')`
                ).run(device, status, userId);
                return { text: `*${device}* 상태를 *${status}*(으)로 업데이트했어요.` };
            }

            case '/상태확인': {
                if (!params) {
                    const rows = this.db.prepare('SELECT * FROM home_status ORDER BY device_name').all() as HomeStatusRow[];
                    return { blocks: listToBlocks('집 전체 상태', rows, r => `*${r.device_name}*: ${r.status}  _(${r.updated_at})_`) };
                }
                const row = this.db.prepare('SELECT * FROM home_status WHERE device_name = ?').get(params) as HomeStatusRow | undefined;
                if (!row) return { text: `*${params}* 상태 정보가 없어요.` };
                return { text: `*${row.device_name}*: ${row.status}  _(${row.updated_at} 기준)_` };
            }

            case '/위치저장': {
                if (!params) return { text: '물건명과 위치를 입력해주세요. 예: "리모컨 소파 옆"' };
                const parts = params.trim().split(/\s+/);
                if (parts.length < 2) return { text: '위치도 함께 입력해주세요.' };
                const item = parts[0]!;
                const location = parts.slice(1).join(' ');
                this.db.prepare(
                    `INSERT INTO item_locations (item_name, location, set_by)
                     VALUES (?, ?, ?)
                     ON CONFLICT(item_name) DO UPDATE SET
                       location = excluded.location,
                       set_by = excluded.set_by,
                       updated_at = datetime('now','localtime')`
                ).run(item, location, userId);
                return { text: `*${item}* 위치 저장: ${location}` };
            }

            case '/위치': {
                if (!params) return { text: '물건명을 입력해주세요. 예: "리모컨"' };
                const row = this.db.prepare('SELECT * FROM item_locations WHERE item_name = ?').get(params) as ItemLocationRow | undefined;
                if (!row) return { text: `*${params}* 위치 정보가 없어요.` };
                return { text: `*${row.item_name}*: ${row.location}  _(${row.updated_at} 기준)_` };
            }

            case '/자산': {
                if (!params) return { text: '카테고리와 금액을 입력해주세요. 예: "현금 500000"' };
                const parts = params.split(/\s+/);
                if (parts.length < 2) return { text: '금액도 함께 입력해주세요.' };
                const category = parts[0]!;
                const amount = parseFloat((parts[1] ?? '').replace(/,/g, ''));
                const note = parts.slice(2).join(' ') || null;
                if (isNaN(amount)) return { text: '금액은 숫자로 입력해주세요.' };
                this.db.prepare('INSERT INTO assets (user_id, category, amount, note) VALUES (?, ?, ?, ?)').run(userId, category, amount, note);
                return { text: `*${category}* ${amount.toLocaleString('ko-KR')}원 기록했어요.` };
            }

            case '/자산확인': {
                const rows = this.db.prepare(`
                    SELECT a.category, a.amount, a.recorded_at
                    FROM assets a
                    INNER JOIN (
                        SELECT category, MAX(id) as max_id
                        FROM assets WHERE user_id = ?
                        GROUP BY category
                    ) latest ON a.id = latest.max_id
                    WHERE a.user_id = ?
                    ORDER BY a.category
                `).all(userId, userId) as Pick<AssetRow, 'category' | 'amount' | 'recorded_at'>[];
                if (!rows.length) return { text: '기록된 자산이 없어요.' };
                const total = rows.reduce((sum, r) => sum + r.amount, 0);
                const lines = rows.map(r => `*${r.category}*: ${r.amount.toLocaleString('ko-KR')}원`).join('\n');
                return {
                    blocks: [
                        headerBlock('자산 현황'),
                        sectionBlock(lines),
                        divider(),
                        sectionBlock(`*합계: ${total.toLocaleString('ko-KR')}원*`),
                    ],
                };
            }

            case '/자산내역': {
                const rows = params
                    ? this.db.prepare('SELECT * FROM assets WHERE user_id = ? AND category = ? ORDER BY recorded_at DESC LIMIT 20').all(userId, params) as AssetRow[]
                    : this.db.prepare('SELECT * FROM assets WHERE user_id = ? ORDER BY recorded_at DESC LIMIT 20').all(userId) as AssetRow[];
                const label = params ? `${params} 내역` : '자산 내역';
                return {
                    blocks: listToBlocks(label, rows, r =>
                        `*${r.category}*: ${r.amount.toLocaleString('ko-KR')}원${r.note ? ` (${r.note})` : ''}\n_${r.recorded_at}_`
                    ),
                };
            }

            case '/레시피저장': {
                if (!params) return { text: '레시피를 입력해주세요.' };
                const lines = params.split('\n').map(l => l.trim()).filter(Boolean);
                const name = lines[0] ?? '';
                const body = lines.slice(1).join('\n');
                const ingredientsMatch = body.match(/재료[:：]?\s*([\s\S]*?)(?=순서[:：]|조리법[:：]|$)/i);
                const stepsMatch = body.match(/(?:순서|조리법)[:：]?\s*([\s\S]*)/i);
                const ingredients = ingredientsMatch?.[1]?.trim() ?? body;
                const steps = stepsMatch?.[1]?.trim() ?? '';
                this.db.prepare('INSERT INTO recipes (user_id, name, ingredients, steps) VALUES (?, ?, ?, ?)').run(userId, name, ingredients, steps);
                return { text: `레시피 *${name}* 저장했어요!` };
            }

            case '/레시피': {
                if (!params) return { text: '레시피 이름을 입력해주세요.' };
                const row = this.db.prepare('SELECT * FROM recipes WHERE name LIKE ? ORDER BY created_at DESC LIMIT 1').get(`%${params}%`) as RecipeRow | undefined;
                if (!row) return { text: `*${params}* 레시피를 찾지 못했어요.` };
                const blocks: KnownBlock[] = [headerBlock(`🍳 ${row.name}`), divider(), sectionBlock(`*재료*\n${row.ingredients}`)];
                if (row.steps) { blocks.push(divider()); blocks.push(sectionBlock(`*조리 순서*\n${row.steps}`)); }
                return { blocks };
            }

            case '/레시피목록': {
                const rows = this.db.prepare('SELECT id, name, created_at FROM recipes ORDER BY created_at DESC LIMIT 20').all() as Pick<RecipeRow, 'id' | 'name' | 'created_at'>[];
                if (!rows.length) return { text: '저장된 레시피가 없어요.' };
                const list = rows.map((r, i) => `${i + 1}. *${r.name}*`).join('\n');
                return { blocks: [headerBlock('레시피 목록'), sectionBlock(list)] };
            }

            case '/구매': {
                const m = params.match(QTY_REGEX);
                if (!m) return { text: '형식: "달걀 10개" (수량+단위 필수)' };
                const itemName = (m[1] ?? '').trim();
                const qty = parseFloat(m[2] ?? '0');
                const unit = m[3] ?? '';
                let itemRow = this.db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(itemName) as GroceryItemRow | undefined;
                if (!itemRow) {
                    this.db.prepare('INSERT INTO grocery_items (name, unit) VALUES (?, ?)').run(itemName, unit);
                    itemRow = this.db.prepare('SELECT * FROM grocery_items WHERE name = ?').get(itemName) as GroceryItemRow;
                }
                this.db.prepare('INSERT INTO grocery_purchases (item_id, qty) VALUES (?, ?)').run(itemRow.id, qty);
                return { text: `*${itemName}* ${qty}${unit} 구매 기록 완료` };
            }

            case '/재고': {
                const items = this.db.prepare('SELECT * FROM grocery_items ORDER BY name').all() as GroceryItemRow[];
                if (!items.length) return { text: '기록된 식재료가 없어요. 구매 기록부터 추가해보세요.' };
                const purchaseStmt = this.db.prepare(
                    'SELECT purchased_at FROM grocery_purchases WHERE item_id = ? ORDER BY purchased_at ASC'
                );
                const shortage: string[] = [];
                const imminent: string[] = [];
                const ok: string[] = [];
                const insufficient: string[] = [];
                items.forEach(item => {
                    const purchases = purchaseStmt.all(item.id) as Pick<GroceryPurchaseRow, 'purchased_at'>[];
                    if (purchases.length < 2) {
                        insufficient.push(`📊 *${item.name}*: 구매 이력 부족 (${purchases.length}회) — 예측 불가`);
                        return;
                    }
                    const dates = purchases.map(p => new Date(p.purchased_at).getTime());
                    let totalInterval = 0;
                    for (let i = 1; i < dates.length; i++) {
                        totalInterval += (dates[i]! - dates[i - 1]!) / (1000 * 60 * 60 * 24);
                    }
                    const avgInterval = totalInterval / (dates.length - 1);
                    const lastDate = new Date(purchases[purchases.length - 1]!.purchased_at).getTime();
                    const daysSinceLast = (Date.now() - lastDate) / (1000 * 60 * 60 * 24);
                    const daysRemaining = Math.round(avgInterval - daysSinceLast);
                    if (daysRemaining <= 0) {
                        shortage.push(`⚠️ *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
                    } else if (daysRemaining <= 3) {
                        imminent.push(`🔔 *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
                    } else {
                        ok.push(`✅ *${item.name}*: 마지막 구매 ${Math.round(daysSinceLast)}일 전, 평균 ${Math.round(avgInterval)}일 주기 (약 ${daysRemaining}일 남음)`);
                    }
                });
                const blocks: KnownBlock[] = [headerBlock('재고 현황')];
                if (shortage.length) { blocks.push(sectionBlock('*부족 예상*')); blocks.push(sectionBlock(shortage.join('\n'))); blocks.push(divider()); }
                if (imminent.length) { blocks.push(sectionBlock('*구매 임박*')); blocks.push(sectionBlock(imminent.join('\n'))); blocks.push(divider()); }
                if (ok.length) { blocks.push(sectionBlock('*여유 있음*')); blocks.push(sectionBlock(ok.join('\n'))); }
                if (insufficient.length) {
                    if (ok.length || imminent.length || shortage.length) blocks.push(divider());
                    blocks.push(sectionBlock('*데이터 부족*')); blocks.push(sectionBlock(insufficient.join('\n')));
                }
                return { blocks };
            }

            default:
                return { text: '해당 명령어를 처리할 수 없어요.' };
        }
    }
}
