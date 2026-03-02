# homeAssistant Bot — 인터페이스 가이드

가족용 홈 서버 Slack 봇. TypeScript + class 기반 아키텍처로 작성되어 있으며, 모든 슬래시 커맨드는 공통 인터페이스와 추상 클래스 계층을 통해 구현됩니다.

---

## 아키텍처 개요

```
ICommand (interface)
└── BaseCommand (abstract class)
    ├── SharedableCommand (abstract class)
    │   └── TodoCommand, MemoCommand, ScheduleCommand, RecipeCommand, AssetCommand
    └── UpsertCommand (abstract class)
        └── HomeStatusCommand, ItemLocationCommand
    (직접 상속)
    └── GroceryCommand, HelpCommand
```

`app.ts`에서 모든 커맨드 인스턴스를 생성하고, `register(app)` 하나만 호출합니다.

```ts
const commands = [new TodoCommand(db), new ScheduleCommand(db), ...];
commands.forEach((cmd) => cmd.register(app));
```

---

## 핵심 인터페이스

### `ICommand` — `src/core/ICommand.ts`

모든 커맨드 클래스가 구현해야 하는 루트 인터페이스입니다.

```ts
interface ICommand {
    register(app: App): void;
}
```

| 메서드 | 설명 |
|--------|------|
| `register(app)` | Slack `App` 인스턴스를 받아 슬래시 커맨드 핸들러를 등록. 공개 API는 이것 하나뿐. |

---

### `SlackResponse` — `src/types/index.ts`

`respond()`에 전달하는 응답 객체 타입입니다.

```ts
type ResponseType = 'ephemeral' | 'in_channel';

interface SlackResponse {
    text?: string;
    blocks?: KnownBlock[];
    response_type: ResponseType;
}
```

| 필드 | 설명 |
|------|------|
| `text` | 단순 텍스트 응답 (blocks 없을 때) |
| `blocks` | Slack Block Kit 블록 배열 |
| `response_type` | `ephemeral`: 명령 실행자만 보임 / `in_channel`: 채널 전체 공개 |

---

### `ParsedShared` — `src/types/index.ts`

공유 여부 파싱 결과를 담는 값 객체입니다.

```ts
interface ParsedShared {
    isShared: boolean;
    content: string;
}
```

커맨드 텍스트가 `"공유 ..."` 로 시작하면 `isShared: true`, 나머지 텍스트가 `content`에 담깁니다.

---

## 추상 클래스

### `BaseCommand` — `src/core/BaseCommand.ts`

모든 커맨드의 기반 클래스. `ICommand`를 구현하고 응답 생성 헬퍼를 제공합니다.

```ts
abstract class BaseCommand implements ICommand {
    constructor(protected readonly db: Database) {}

    abstract register(app: App): void;

    protected ok(text: string, inChannel = false): SlackResponse
    protected err(text: string): SlackResponse
    protected blocks(title: string, lines: string | string[]): SlackResponse
    protected blocksWithTotal(title: string, lines: string, total: string): SlackResponse
}
```

| 메서드 | 응답 타입 | 설명 |
|--------|-----------|------|
| `ok(text, inChannel?)` | ephemeral \| in_channel | 성공 텍스트 응답. `inChannel=true`이면 채널 공개 |
| `err(text)` | ephemeral | 오류 메시지. 항상 본인만 보임 |
| `blocks(title, lines)` | ephemeral | 헤더 + 섹션 블록 응답 |
| `blocksWithTotal(title, lines, total)` | ephemeral | 헤더 + 본문 + 구분선 + 합계 블록 응답 |

DB는 생성자 인자로 주입받으며, `better-sqlite3`의 동기 API를 사용합니다.

---

### `SharedableCommand` — `src/core/SharedableCommand.ts`

개인 데이터와 공유 데이터를 모두 다루는 커맨드용 추상 클래스입니다.

```ts
abstract class SharedableCommand extends BaseCommand {
    protected parseShared(text: string): ParsedShared
    protected sharedWhereClause(userId: string): string
}
```

| 메서드 | 설명 |
|--------|------|
| `parseShared(text)` | `"공유 내용"` → `{ isShared: true, content: "내용" }` 파싱 |
| `sharedWhereClause(userId)` | `(user_id = '...' OR is_shared = 1)` SQL 조건 생성 |

**상속 클래스**: `TodoCommand`, `MemoCommand`, `ScheduleCommand`, `RecipeCommand`, `AssetCommand`

---

### `UpsertCommand` — `src/core/UpsertCommand.ts`

키-값 형태로 레코드를 insert-or-update하는 커맨드용 추상 클래스입니다.

```ts
abstract class UpsertCommand extends BaseCommand {
    protected abstract readonly tableName: string;
    protected abstract readonly nameCol: string;
    protected abstract readonly valueCol: string;

    protected splitTwo(text: string): [string, string] | null
    protected performUpsert(name: string, value: string, userId: string): void
}
```

| 멤버 | 설명 |
|------|------|
| `tableName` | 대상 테이블 이름 (서브클래스에서 정의) |
| `nameCol` | 키 역할을 하는 컬럼 이름 (UNIQUE 제약 필요) |
| `valueCol` | 값 역할을 하는 컬럼 이름 |
| `splitTwo(text)` | `"key value"` → `["key", "value"]` 분리. 실패 시 `null` |
| `performUpsert(name, value, userId)` | `ON CONFLICT DO UPDATE` 방식으로 단일 레코드 저장 |

**상속 클래스**: `HomeStatusCommand` (`device_name`, `status`), `ItemLocationCommand` (`item_name`, `location`)

---

## DB Row 타입 — `src/types/index.ts`

SQLite 쿼리 결과를 타입 안전하게 다루기 위한 인터페이스들입니다.

| 타입 | 테이블 | 주요 필드 |
|------|--------|-----------|
| `TodoRow` | `todos` | `content`, `is_shared`, `is_done`, `due_date`, `done_at` |
| `MemoRow` | `memos` | `title`, `content`, `tags`, `is_shared` |
| `ScheduleRow` | `schedules` | `title`, `event_date`, `end_date`, `is_shared` |
| `HomeStatusRow` | `home_status` | `device_name`, `status`, `set_by` |
| `ItemLocationRow` | `item_locations` | `item_name`, `location`, `set_by` |
| `AssetRow` | `assets` | `category`, `amount`, `note`, `recorded_at` |
| `RecipeRow` | `recipes` | `name`, `ingredients`, `steps`, `tags`, `servings` |
| `GroceryItemRow` | `grocery_items` | `name`, `unit`, `current_qty`, `min_qty` |
| `GroceryTransactionRow` | `grocery_transactions` | `item_id`, `delta`, `user_id` |

`is_shared` 컬럼은 SQLite의 `INTEGER` 타입으로 저장되며, `0` = 개인, `1` = 가족 공유입니다.

---

## 유틸리티

### `blocks.ts` — `src/formatters/blocks.ts`

Slack Block Kit 블록 생성 헬퍼 함수 모음입니다.

```ts
function headerBlock(text: string): KnownBlock
function sectionBlock(mrkdwn: string): KnownBlock
function divider(): KnownBlock
function listToBlocks<T>(header: string, items: T[], formatFn: (item: T) => string): KnownBlock[]
```

| 함수 | 설명 |
|------|------|
| `headerBlock(text)` | 굵은 헤더 텍스트 블록 |
| `sectionBlock(mrkdwn)` | Markdown 텍스트 섹션 블록 |
| `divider()` | 수평 구분선 블록 |
| `listToBlocks(header, items, fn)` | 아이템 배열을 포맷 함수로 변환해 블록 목록 생성. 빈 배열이면 "항목 없음" 메시지 반환 |

---

### `claudeClient.ts` — `src/nlp/claudeClient.ts`

한국어 날짜 표현을 ISO 8601로 파싱하는 NLP 헬퍼입니다. `claude-haiku-4-5` 모델을 사용합니다.

```ts
async function parseDate(text: string): Promise<string | null>
async function parseDateRange(text: string): Promise<{ from: string | null; to: string | null }>
```

| 함수 | 입력 예시 | 출력 예시 |
|------|-----------|-----------|
| `parseDate(text)` | `"내일 오후 3시"` | `"2026-02-28T15:00"` |
| `parseDateRange(text)` | `"이번 주"` | `{ from: "2026-02-24", to: "2026-03-02" }` |

`/일정`과 `/일정목록` 커맨드에서만 사용됩니다. 파싱 실패 시 `null`을 반환합니다.

---

## 새 커맨드 추가 방법

1. **기반 클래스 선택**
   - 공유 여부(`공유` 접두사)가 필요한 경우 → `SharedableCommand` 상속
   - 키-값 upsert가 필요한 경우 → `UpsertCommand` 상속
   - 그 외 → `BaseCommand` 상속

2. **커맨드 클래스 작성**

   ```ts
   export class MyCommand extends SharedableCommand {
       register(app: App): void {
           app.command('/내커맨드', async ({ command, ack, respond }) => {
               await ack();
               await respond(this.handle(command.text, command.user_id) as never);
           });
       }

       private handle(text: string, userId: string): SlackResponse {
           // ...
           return this.ok('완료!');
       }
   }
   ```

3. **`app.ts`에 등록**

   ```ts
   const commands = [
       ...,
       new MyCommand(db),
   ];
   ```

4. **Slack 앱 설정에서 슬래시 커맨드 추가** (api.slack.com → Slash Commands)
