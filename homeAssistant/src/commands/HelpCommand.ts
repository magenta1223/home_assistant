import type { App } from '@slack/bolt';
import { BaseCommand } from '../core/BaseCommand';
import { headerBlock, sectionBlock, divider } from '../formatters/blocks';

const HELP_TEXT = `*메모*
\`/메모 내용\` — 메모 저장 (앞에 "공유" 붙이면 가족 공유)
\`/메모목록\` — 내 메모 목록 (\`공유\` 입력 시 공유 메모만)
\`/메모검색 검색어\` — 메모 검색

*일정*
\`/일정 내일 오후 3시 치과\` — 일정 등록 (앞에 "공유" 붙이면 가족 공유)
\`/일정목록\` — 다음 30일 일정 (\`이번 주\`, \`다음 달\` 등 입력 가능)

*집 상태*
\`/상태 에어컨 켜\` — 기기 상태 업데이트
\`/상태확인 에어컨\` — 특정 기기 상태 조회 (비우면 전체)

*물건 위치*
\`/위치저장 리모컨 소파 옆\` — 위치 저장
\`/위치 리모컨\` — 위치 조회

*자산*
\`/자산 현금 500000\` — 자산 기록
\`/자산확인\` — 카테고리별 현재 자산
\`/자산내역 현금\` — 카테고리별 변동 내역

*할 일*
\`/할일 장보기\` — 할 일 추가 (앞에 "공유" 붙이면 가족 공유)
\`/할일목록\` — 할 일 목록 (\`공유\`, \`완료\` 필터 가능)
\`/완료 장보기\` — 완료 처리

*레시피*
\`/레시피저장 김치찌개\\n재료: ...\\n순서: ...\` — 레시피 저장
\`/레시피 김치찌개\` — 레시피 검색
\`/레시피목록\` — 전체 레시피 목록

*장보기 / 재고*
\`/구매 달걀 10개\` — 구매 기록
\`/사용 달걀 3개\` — 사용 기록
\`/재고\` — 재고 현황 + 부족 예측`;

export class HelpCommand extends BaseCommand {
    register(app: App): void {
        app.command('/도움말', async ({ command, ack, respond }) => {
            await ack();
            await respond({
                blocks: [headerBlock('홈 어시스턴트 도움말'), divider(), sectionBlock(HELP_TEXT)],
                response_type: 'ephemeral',
            } as never);
        });
    }
}
