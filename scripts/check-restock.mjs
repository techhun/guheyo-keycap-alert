import fs from 'node:fs';

const WATCHLIST_PATH = 'restock-watchlist.json';
const STATE_PATH = 'restock-state.json';
const DISCORD_WEBHOOK_URL = (process.env.RESTOCK_DISCORD_WEBHOOK_URL || '').trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const clean = (value) => String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();

function readJson(path, fallback) {
  try {
    const parsed = JSON.parse(fs.readFileSync(path, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : fallback;
  } catch {
    return fallback;
  }
}

function normalizeProductUrl(value) {
  const url = new URL(clean(value));
  if (!['swagkeys.com', 'www.swagkeys.com'].includes(url.hostname.toLowerCase())) {
    throw new Error(`unsupported restock host: ${url.hostname}`);
  }
  url.search = '';
  url.hash = '';
  url.hostname = 'swagkeys.com';
  url.protocol = 'https:';
  url.pathname = url.pathname.replace(/\/+$/, '');
  if (!/^\/products\/[^/]+$/.test(url.pathname)) {
    throw new Error(`not a SWAGKEYS product URL: ${url.toString()}`);
  }
  return url.toString();
}

function normalizeSelections(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value)
    .map(([key, selected]) => [clean(key), clean(selected)])
    .filter(([key, selected]) => key && selected)
    .sort(([a], [b]) => a.localeCompare(b)));
}

async function fetchProduct(productUrl) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(`${productUrl}.js`, {
        headers: {
          'Accept': 'application/json,text/javascript,*/*;q=0.8',
          'User-Agent': 'Mozilla/5.0 keyboard-alert/1.0'
        },
        signal: AbortSignal.timeout(15000)
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const product = await response.json();
      if (!Array.isArray(product?.variants) || !Array.isArray(product?.options)) {
        throw new Error('invalid Shopify product payload');
      }
      return product;
    } catch (error) {
      lastError = error;
      console.warn(`[restock] ${productUrl} attempt ${attempt} failed: ${error?.message || error}`);
      if (attempt < 3) await sleep(attempt * 1500);
    }
  }
  throw lastError;
}

function optionIndexMap(product) {
  const map = new Map();
  for (const option of product.options || []) {
    const position = Number(option.position);
    if (!Number.isInteger(position) || position < 1 || position > 3) continue;
    map.set(clean(option.name).toLowerCase(), position - 1);
  }
  return map;
}

function variantOptions(variant) {
  return [variant.option1, variant.option2, variant.option3].map(clean);
}

function matchingVariants(product, selections) {
  const indexes = optionIndexMap(product);
  const normalized = Object.entries(selections).map(([name, value]) => ({
    name,
    key: clean(name).toLowerCase(),
    value: clean(value)
  }));

  for (const selection of normalized) {
    if (selection.value === '*' || selection.value === '상관없음') continue;
    if (!indexes.has(selection.key)) {
      throw new Error(`option not found: ${selection.name}`);
    }
  }

  return product.variants.filter((variant) => {
    const values = variantOptions(variant);
    return normalized.every((selection) => {
      if (selection.value === '*' || selection.value === '상관없음') return true;
      const index = indexes.get(selection.key);
      return clean(values[index]).toLowerCase() === selection.value.toLowerCase();
    });
  });
}

function stableItemId(item, productUrl, selections) {
  const explicit = clean(item?.id);
  if (explicit) return explicit;
  const selector = Object.entries(selections)
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
  return `${productUrl}#${selector}`;
}

function variantIds(variants) {
  return variants.map((variant) => String(variant.id)).sort();
}

function formatSelections(selections) {
  const entries = Object.entries(selections);
  if (!entries.length) return '전체 옵션';
  return entries.map(([name, value]) => `• ${name}: ${value === '*' ? '상관없음' : value}`).join('\n');
}

async function postDiscord({ product, productUrl, selections, availableVariants }) {
  if (!DISCORD_WEBHOOK_URL) return false;

  const variantLines = availableVariants
    .slice(0, 8)
    .map((variant) => `• ${clean(variant.title) || `Variant ${variant.id}`}`)
    .join('\n');
  const more = availableVariants.length > 8 ? `\n외 ${availableVariants.length - 8}개` : '';

  const embed = {
    title: `📦 재입고 · ${clean(product.title)}`,
    url: productUrl,
    fields: [
      { name: '감시 조건', value: formatSelections(selections), inline: false },
      { name: '구매 가능한 조합', value: `${variantLines || '확인 필요'}${more}`, inline: false },
      { name: '링크', value: `[🔗 상품 페이지](${productUrl})`, inline: false }
    ],
    footer: { text: 'SWAGKEYS · Restock Watch' },
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
    if (response.ok) return true;
    if (response.status === 429 && attempt < 3) {
      let retryAfter = 1;
      try { retryAfter = Number((await response.json())?.retry_after) || 1; } catch {}
      await sleep(Math.ceil(retryAfter * 1000));
      continue;
    }
    throw new Error(`restock Discord webhook failed: ${response.status} ${await response.text()}`);
  }
  return false;
}

function saveState(state) {
  fs.writeFileSync(STATE_PATH, JSON.stringify({
    version: 1,
    initialized: true,
    updatedAt: new Date().toISOString(),
    items: state.items || {}
  }, null, 2) + '\n');
}

async function main() {
  const watchlist = readJson(WATCHLIST_PATH, { version: 1, items: [] });
  const items = Array.isArray(watchlist.items) ? watchlist.items.filter((item) => item?.enabled !== false) : [];
  const state = readJson(STATE_PATH, { version: 1, initialized: true, updatedAt: null, items: {} });
  state.items ||= {};

  if (items.length === 0) {
    if (Object.keys(state.items).length > 0) {
      state.items = {};
      saveState(state);
      console.log('[restock] watchlist is empty; stale watcher state cleared.');
    } else {
      console.log('[restock] watchlist is empty.');
    }
    return;
  }

  let changed = false;
  const activeIds = new Set();

  for (const item of items) {
    const productUrl = normalizeProductUrl(item.url);
    const selections = normalizeSelections(item.selections);
    const itemId = stableItemId(item, productUrl, selections);
    activeIds.add(itemId);

    const product = await fetchProduct(productUrl);
    const matches = matchingVariants(product, selections);
    if (matches.length === 0) {
      throw new Error(`[restock] no variants matched ${product.title}: ${JSON.stringify(selections)}`);
    }

    const availableVariants = matches.filter((variant) => Boolean(variant.available));
    const available = availableVariants.length > 0;
    const previous = state.items[itemId];

    console.log(`[restock] ${product.title} | ${JSON.stringify(selections)} | matched=${matches.length} available=${availableVariants.length}`);

    if (!previous) {
      state.items[itemId] = {
        productUrl,
        productTitle: clean(product.title),
        selections,
        available,
        matchingVariantIds: variantIds(matches),
        availableVariantIds: variantIds(availableVariants)
      };
      changed = true;
      console.log('[restock] baseline stored without notification.');
      continue;
    }

    if (!previous.available && available) {
      if (!DISCORD_WEBHOOK_URL) {
        console.log('[restock] RESTOCK_DISCORD_WEBHOOK_URL is not configured. Restock remains pending; state was not advanced.');
        continue;
      }
      await postDiscord({ product, productUrl, selections, availableVariants });
      console.log('[restock] restock notification sent.');
    }

    const next = {
      productUrl,
      productTitle: clean(product.title),
      selections,
      available,
      matchingVariantIds: variantIds(matches),
      availableVariantIds: variantIds(availableVariants)
    };
    if (JSON.stringify(previous) !== JSON.stringify(next)) {
      state.items[itemId] = next;
      changed = true;
    }
  }

  for (const itemId of Object.keys(state.items)) {
    if (!activeIds.has(itemId)) {
      delete state.items[itemId];
      changed = true;
    }
  }

  if (changed) {
    saveState(state);
    console.log('[restock] state updated.');
  } else {
    console.log('[restock] state unchanged.');
  }
}

await main();
