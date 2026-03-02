import type { KnownBlock } from '@slack/types';

export function headerBlock(text: string): KnownBlock {
    return { type: 'header', text: { type: 'plain_text', text, emoji: true } };
}

export function sectionBlock(mrkdwn: string): KnownBlock {
    return { type: 'section', text: { type: 'mrkdwn', text: mrkdwn } };
}

export function divider(): KnownBlock {
    return { type: 'divider' };
}

export function listToBlocks<T>(
    header: string,
    items: T[],
    formatFn: (item: T) => string
): KnownBlock[] {
    if (items.length === 0) {
        return [sectionBlock(`_${header}에 해당하는 항목이 없어요._`)];
    }
    const blocks: KnownBlock[] = [headerBlock(header), divider()];
    items.forEach(item => blocks.push(sectionBlock(formatFn(item))));
    return blocks;
}
