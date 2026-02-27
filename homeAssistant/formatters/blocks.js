function headerBlock(text) {
    return { type: 'header', text: { type: 'plain_text', text, emoji: true } };
}

function sectionBlock(mrkdwn) {
    return { type: 'section', text: { type: 'mrkdwn', text: mrkdwn } };
}

function divider() {
    return { type: 'divider' };
}

function listToBlocks(header, items, formatFn) {
    if (!items.length) return [sectionBlock(`_${header}에 해당하는 항목이 없어요._`)];
    const blocks = [headerBlock(header), divider()];
    items.forEach(item => blocks.push(sectionBlock(formatFn(item))));
    return blocks;
}

module.exports = { headerBlock, sectionBlock, divider, listToBlocks };
