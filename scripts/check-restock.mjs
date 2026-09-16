import { checkRestock } from './restock/engine.mjs';
import { optionSummary } from './restock/providers/common.mjs';
import { readLocalJson, writeLocalJson } from './restock/storage.mjs';

const WATCHLIST_PATH = 'restock-watchlist.json';
const STATE_PATH = 'restock-state.json';
const DISCORD_WEBHOOK_URL = (process.env.RESTOCK_DISCORD_WEBHOOK_URL || '').trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function postDiscord({ item, snapshot, match }) {
  if (!DISCORD_WEBHOOK_URL) throw new Error('RESTOCK_DISCORD_WEBHOOK_URL is not configured; restock transition remains pending');
  const variants = match.availableVariants
    .slice(0, 8)
    .map((variant) => `• ${variant.title || `Variant ${variant.id}`}`)
    .join('\n');
  const extra = match.availableVariants.length > 8 ? `\n외 ${match.availableVariants.length - 8}개` : '';
  const addons = match.selectedAddons
    .map(({ group, item: addon }) => `• ${group.name}: ${addon.label || addon.value}`)
    .join('\n');

  const fields = [
    { name: '감시 조건', value: optionSummary(item.selections), inline: false },
    { name: '구매 가능한 조합', value: `${variants || '구매 가능'}${extra}`, inline: false }
  ];
  if (addons) fields.push({ name: '선택 추가상품', value: addons, inline: false });
  fields.push({ name: '링크', value: `[🔗 상품 페이지](${snapshot.canonicalUrl})`, inline: false });

  const embed = {
    title: `📦 재입고 · ${snapshot.title}`,
    url: snapshot.canonicalUrl,
    fields,
    footer: { text: `${snapshot.provider} · Restock Watch` },
    timestamp: new Date().toISOString()
  };

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await fetch(DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'Keyboard Alert · Restock',
        allowed_mentions: { parse: [] },
        embeds: [embed]
      })
    });
    if (response.ok) return;
    if (response.status === 429 && attempt < 3) {
      let retryAfter = 1;
      try { retryAfter = Number((await response.json())?.retry_after) || 1; } catch {}
      await sleep(Math.ceil(retryAfter * 1000));
      continue;
    }
    throw new Error(`restock Discord webhook failed: ${response.status} ${await response.text()}`);
  }
}

const watchlist = readLocalJson(WATCHLIST_PATH, { version: 2, items: [] });
const actionWatchlist = {
  ...watchlist,
  items: (Array.isArray(watchlist.items) ? watchlist.items : []).filter((item) => !item?.monitor || item.monitor === 'actions')
};
const state = readLocalJson(STATE_PATH, { version: 2, initialized: true, updatedAt: null, items: {} });
const result = await checkRestock({ watchlist: actionWatchlist, state, onRestock: postDiscord });

if (result.changed) {
  writeLocalJson(STATE_PATH, result.state);
  console.log(`[restock] state updated after checking ${result.checked} item(s).`);
} else {
  console.log(`[restock] state unchanged after checking ${result.checked} item(s).`);
}

if (result.errors.length) {
  throw new AggregateError(result.errors.map(({ error }) => error), `${result.errors.length} restock item(s) failed`);
}
