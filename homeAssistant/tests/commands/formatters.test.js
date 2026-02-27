const { headerBlock, sectionBlock, divider, listToBlocks } = require('../../formatters/blocks');

describe('headerBlock', () => {
    test('returns header type with plain_text', () => {
        const block = headerBlock('제목');
        expect(block.type).toBe('header');
        expect(block.text.type).toBe('plain_text');
        expect(block.text.text).toBe('제목');
    });
});

describe('sectionBlock', () => {
    test('returns section type with mrkdwn', () => {
        const block = sectionBlock('*굵게*');
        expect(block.type).toBe('section');
        expect(block.text.type).toBe('mrkdwn');
        expect(block.text.text).toBe('*굵게*');
    });
});

describe('divider', () => {
    test('returns divider type', () => {
        expect(divider().type).toBe('divider');
    });
});

describe('listToBlocks', () => {
    test('빈 배열이면 "항목 없음" 메시지 반환', () => {
        const blocks = listToBlocks('제목', [], () => '');
        expect(blocks).toHaveLength(1);
        expect(blocks[0].text.text).toContain('항목이 없어요');
    });

    test('항목 있으면 header + divider + 항목 수만큼 section 반환', () => {
        const blocks = listToBlocks('목록', ['a', 'b'], x => x);
        expect(blocks[0].type).toBe('header');
        expect(blocks[1].type).toBe('divider');
        expect(blocks).toHaveLength(4); // header + divider + 2 items
    });

    test('formatFn 결과가 section text에 반영됨', () => {
        const blocks = listToBlocks('목록', [{ name: '항목1' }], item => item.name);
        const section = blocks.find(b => b.type === 'section');
        expect(section.text.text).toBe('항목1');
    });
});
