import crypto from 'node:crypto';
import { inspectProduct } from './providers/index.mjs';
import { clean, matchSnapshot, normalizeSelectionList } from './providers/common.mjs';

function legacySelections(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return Object.entries(value).map(([name, selected], index) => ({
    key: `option${index + 1}`,
    name: clean(name),
    kind: 'variant',
    value: clean(selected),
    label: clean(selected)
  }));
}

export function normalizeWatchItem(item) {
  const selections = Array.isArray(item?.selections)
    ? normalizeSelectionList(item.selections)
    : legacySelections(item?.selections);
  return {
    ...item,
    id: clean(item?.id),
    provider: clean(item?.provider),
    url: clean(item?.url || item?.canonicalUrl),
    canonicalUrl: clean(item?.canonicalUrl),
    title: clean(item?.title),
    enabled: item?.enabled !== false,
    selections
  };
}

function stateItemId(item) {
  if (item.id) return item.id;
  const input = JSON.stringify({ provider: item.provider, url: item.canonicalUrl || item.url, selections: item.selections });
  return crypto.createHash('sha256').update(input).digest('hex').slice(0, 24);
}

function ids(values) {
  return values.map((value) => String(value.id)).sort();
}

function comparableState({ item, snapshot, match }) {
  return {
    provider: snapshot.provider,
    productKey: snapshot.productKey,
    canonicalUrl: snapshot.canonicalUrl,
    productTitle: snapshot.title,
    selections: item.selections,
    available: Boolean(match.available),
    matchingVariantIds: ids(match.matchingVariants),
    availableVariantIds: ids(match.availableVariants)
  };
}

export async function checkRestock({ watchlist, state, onRestock = async () => {} }) {
  const items = (Array.isArray(watchlist?.items) ? watchlist.items : [])
    .map(normalizeWatchItem)
    .filter((item) => item.enabled && item.url);
  const nextState = {
    version: 2,
    initialized: true,
    updatedAt: state?.updatedAt || null,
    items: { ...(state?.items || {}) }
  };
  const activeIds = new Set();
  const snapshotCache = new Map();
  const errors = [];
  let changed = false;

  for (const item of items) {
    let snapshot;
    try {
      const cacheKey = `${item.provider || 'auto'}|${item.canonicalUrl || item.url}`;
      if (!snapshotCache.has(cacheKey)) {
        snapshotCache.set(cacheKey, inspectProduct(item.canonicalUrl || item.url, item.provider));
      }
      snapshot = await snapshotCache.get(cacheKey);
      if (!item.provider) item.provider = snapshot.provider;
      if (!item.canonicalUrl) item.canonicalUrl = snapshot.canonicalUrl;
      if (!item.title) item.title = snapshot.title;

      const id = stateItemId(item);
      activeIds.add(id);
      const match = matchSnapshot(snapshot, item.selections);
      if (match.matchingVariants.length === 0) {
        throw new Error(`no variants matched: ${snapshot.title}`);
      }
      const previous = nextState.items[id];
      const current = comparableState({ item, snapshot, match });
      console.log(`[restock] ${snapshot.provider} | ${snapshot.title} | ${id} | matched=${match.matchingVariants.length} available=${match.availableVariants.length} overall=${current.available}`);

      if (!previous) {
        nextState.items[id] = current;
        changed = true;
        console.log('[restock] baseline stored without notification.');
        continue;
      }

      if (!previous.available && current.available) {
        await onRestock({ id, item, snapshot, match });
        console.log('[restock] restock notification sent.');
      }

      if (JSON.stringify(previous) !== JSON.stringify(current)) {
        nextState.items[id] = current;
        changed = true;
      }
    } catch (error) {
      errors.push({ item, error });
      console.error(`[restock] failed for ${item.url}: ${error?.stack || error}`);
      if (item.id) activeIds.add(item.id);
    }
  }

  for (const id of Object.keys(nextState.items)) {
    if (!activeIds.has(id)) {
      delete nextState.items[id];
      changed = true;
    }
  }

  if (changed) nextState.updatedAt = new Date().toISOString();
  return { state: nextState, changed, errors, checked: items.length };
}
