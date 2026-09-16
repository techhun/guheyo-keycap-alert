import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const STATE_PATH = 'prototypist-state.json';
const DISCORD_WEBHOOK_URL = (process.env.PROTOTYPIST_DISCORD_WEBHOOK_URL || '').trim();
const STATUSES = [
  'In Group Buy',
  'In Manufacturing',
  'Shipping to Prototypist',
  'In Quality Control',
  'Shipping to Customers',
  'Completed'
];
const SOURCES = [
  { id: 'keyboard', label: 'Keyboard', emoji: '⌨️', url: 'https://prototypist.notion.site/57d8169dada041f6b52b1d091e3e2c97?v=449c85bd9a29455fa8d1330e23680c5a' },
  { id: 'gmk', label: 'GMK', emoji: '🇩🇪', url: 'https://prototypist.notion.site/dfedac384a2b494c9137be7638b11e93?v=11769ac8c9764aafb907fe3d65fb0c50' },
  { id: 'keyset', label: 'Keyset', emoji: '🧢', url: 'https://prototypist.notion.site/119c46c4a89140e98b295ed0102eb94e?v=a13cd09931964dd596b125ea0013f5c6' }
];

const clean = (value) => String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();
const truncate = (value, maxLength = 1000) => {
  const text = String(value ?? '')
    .replace(/\r/g, '')
    .split('\n')
    .map(clean)
    .filter(Boolean)
    .join('\n') || '—';
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1).trimEnd()}…`;
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const DETAIL_TIMEOUT_MS = 12000;

function normalizeUrl(value) {
  try {
    const url = new URL(value);
    url.search = '';
    url.hash = '';
    return url.toString();
  } catch {
    return clean(value);
  }
}

function parseCardText(value) {
  const text = clean(value);
  const match = text.match(/\s+(All batches TBC|(?:Early\s+|Late\s+)?Q[1-4](?:\s*-\s*Q[1-4])?(?:\s+\d{4})?|TBC)$/i);
  return match
    ? { product: clean(text.slice(0, match.index)), expectedShipping: clean(match[1]) }
    : { product: text, expectedShipping: '' };
}

async function extractPage(page, source) {
  await page.goto(source.url, { waitUntil: 'domcontentloaded', timeout: 60000 });

  await page.waitForFunction((statuses) => {
    const title = document.title || '';
    if (/just a moment/i.test(title)) return true;

    const body = document.body?.innerText || '';
    const statusCount = statuses.filter((status) => body.includes(status)).length;
    return statusCount >= 4 && document.querySelectorAll('a').length >= 5;
  }, STATUSES, { timeout: 25000 }).catch(() => {});

  if (/just a moment/i.test(await page.title())) {
    throw new Error(`${source.label} was blocked by the Notion challenge page`);
  }

  await page.waitForTimeout(1200);

  return page.evaluate((statuses) => {
    const cleanText = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
    const visible = (el) => {
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
    };

    const headers = [];
    for (const status of statuses) {
      const candidates = [...document.querySelectorAll('*')]
        .filter((el) => visible(el) && cleanText(el.innerText) === status)
        .sort((a, b) => {
          const ar = a.getBoundingClientRect();
          const br = b.getBoundingClientRect();
          const ap = a.getAttribute('role') === 'button' ? 0 : 1;
          const bp = b.getAttribute('role') === 'button' ? 0 : 1;
          return ap !== bp ? ap - bp : (ar.width * ar.height) - (br.width * br.height);
        });

      if (!candidates[0]) continue;
      const rect = candidates[0].getBoundingClientRect();
      headers.push({ status, centerX: rect.left + rect.width / 2 });
    }

    const currentPath = location.pathname.replace(/\/$/, '');
    const anchors = [...document.querySelectorAll('a')]
      .map((a) => {
        const rect = a.getBoundingClientRect();
        return {
          text: cleanText(a.innerText),
          href: a.href || '',
          centerX: rect.left + rect.width / 2,
          width: rect.width,
          height: rect.height
        };
      })
      .filter((row) => row.text && row.width > 0 && row.height > 0)
      .filter((row) => {
        try {
          const url = new URL(row.href);
          return url.hostname === 'prototypist.notion.site'
            && url.pathname.replace(/\/$/, '') !== currentPath
            && !url.hash;
        } catch {
          return false;
        }
      });

    const rows = anchors.map((row) => {
      let nearest = null;
      for (const header of headers) {
        const distance = Math.abs(row.centerX - header.centerX);
        if (!nearest || distance < nearest.distance) nearest = { ...header, distance };
      }
      return {
        ...row,
        status: nearest?.status || '',
        statusDistance: nearest?.distance ?? 9999
      };
    });

    return {
      title: document.title,
      headerCount: headers.length,
      anchorCount: anchors.length,
      rows
    };
  }, STATUSES);
}

async function fetchSource(context, source) {
  let lastError;

  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const page = await context.newPage();

    try {
      const extracted = await extractPage(page, source);
      if (extracted.headerCount < 4) {
        throw new Error(`${source.label} rendered only ${extracted.headerCount} status headers; anchors=${extracted.anchorCount}; title=${extracted.title}`);
      }

      const deduped = new Map();
      for (const raw of extracted.rows) {
        if (!STATUSES.includes(raw.status) || raw.statusDistance > 180) continue;

        const itemUrl = normalizeUrl(raw.href);
        const { product, expectedShipping } = parseCardText(raw.text);
        if (!itemUrl || !product) continue;

        deduped.set(itemUrl, {
          sourceId: source.id,
          sourceLabel: source.label,
          sourceEmoji: source.emoji,
          sourceUrl: source.url,
          itemUrl,
          product,
          status: raw.status,
          expectedShipping
        });
      }

      const rows = [...deduped.values()];
      if (rows.length < 5) {
        throw new Error(`${source.label} returned too few rows: ${rows.length}; anchors=${extracted.anchorCount}; headers=${extracted.headerCount}`);
      }

      console.log(`Proto[Typist] ${source.label} rows: ${rows.length} (headers ${extracted.headerCount}, anchors ${extracted.anchorCount})`);
      console.log(`Proto[Typist] ${source.label} sample:`, JSON.stringify(rows.slice(0, 3), null, 2));
      return rows;
    } catch (error) {
      lastError = error;
      console.warn(`Proto[Typist] ${source.label} attempt ${attempt} failed: ${error?.message || error}`);
      if (attempt < 3) await sleep(5000);
    } finally {
      await page.close();
    }
  }

  throw lastError;
}

async function fetchRows() {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const context = await browser.newContext({
    viewport: { width: 2200, height: 1600 },
    locale: 'en-GB',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
  });

  try {
    const all = [];
    for (const source of SOURCES) {
      all.push(...await fetchSource(context, source));
      await sleep(1500);
    }
    return all;
  } finally {
    await context.close();
    await browser.close();
  }
}

async function extractDetailNote(context, row) {
  if (!row.itemUrl) return '';

  const page = await context.newPage();
  page.setDefaultTimeout(DETAIL_TIMEOUT_MS);

  try {
    await page.goto(row.itemUrl, { waitUntil: 'domcontentloaded', timeout: DETAIL_TIMEOUT_MS });
    if (/just a moment/i.test(await page.title())) throw new Error('blocked by the Notion challenge page');
    await page.waitForTimeout(800);

    return await page.evaluate(({ product, statuses }) => {
      const cleanText = (value) => String(value ?? '').replace(/\r/g, '').replace(/[ \t]+/g, ' ').trim();
      const root = document.querySelector('.notion-page-content') || document.querySelector('main') || document.body;
      const ignored = new Set([
        cleanText(product),
        ...statuses,
        'Expected Shipping',
        'Estimated Shipping',
        'Status',
        'Updates',
        'Update'
      ].map((value) => value.toLowerCase()));

      const lines = cleanText(root?.innerText)
        .split('\n')
        .map(cleanText)
        .filter(Boolean)
        .filter((line) => !ignored.has(line.toLowerCase()))
        .filter((line) => !/^https?:\/\//i.test(line))
        .filter((line) => !/^(early |late )?q[1-4](\s*-\s*q[1-4])?(\s+\d{4})?$/i.test(line))
        .filter((line) => !/^all batches tbc$/i.test(line));

      const unique = [...new Set(lines)];
      return unique.filter((line) => line.length >= 12).slice(0, 12).join('\n');
    }, { product: row.product, statuses: STATUSES });
  } finally {
    await page.close();
  }
}

async function addDetailNotes(changes) {
  if (changes.length === 0) return;

  let browser;
  try {
    browser = await chromium.launch({ headless: true, channel: 'chrome' });
  } catch (error) {
    console.warn(`Proto[Typist] detail lookups skipped: ${error?.message || error}`);
    return;
  }

  const context = await browser.newContext({
    viewport: { width: 1400, height: 1000 },
    locale: 'en-GB',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
  });

  try {
    for (const change of changes) {
      try {
        const detailNote = clean(await extractDetailNote(context, change.row));
        if (detailNote) change.detailNote = detailNote;
      } catch (error) {
        console.warn(`Proto[Typist] detail lookup skipped for ${change.row.product}: ${error?.message || error}`);
      }
    }
  } finally {
    await context.close();
    await browser.close();
  }
}

function loadState() {
  try {
    const parsed = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

function saveState(rows) {
  fs.writeFileSync(
    STATE_PATH,
    JSON.stringify({
      version: 1,
      initialized: true,
      updatedAt: new Date().toISOString(),
      rows
    }, null, 2) + '\n'
  );
}

function buildMap(rows) {
  const map = new Map();
  for (const row of rows) {
    map.set(
      normalizeUrl(row.itemUrl) || `${row.sourceId}|${clean(row.product).toLowerCase()}`,
      row
    );
  }
  return map;
}

function sourceCounts(rows) {
  return Object.fromEntries(
    SOURCES.map((source) => [source.id, rows.filter((row) => row.sourceId === source.id).length])
  );
}

function diffRows(previousRows, currentRows) {
  const previous = buildMap(previousRows);
  const current = buildMap(currentRows);
  const changes = [];

  for (const [key, row] of current) {
    const before = previous.get(key);
    if (!before) {
      changes.push({ kind: 'added', row });
      continue;
    }

    const fields = [];
    if (clean(before.status) !== clean(row.status)) {
      fields.push({ label: '상태', before: before.status || '—', after: row.status || '—' });
    }
    if (clean(before.expectedShipping) !== clean(row.expectedShipping)) {
      fields.push({ label: '예상 고객 배송', before: before.expectedShipping || '—', after: row.expectedShipping || '—' });
    }
    if (clean(before.product) !== clean(row.product)) {
      fields.push({ label: '제품명', before: before.product || '—', after: row.product || '—' });
    }

    if (fields.length) changes.push({ kind: 'changed', row, before, fields });
  }

  for (const [key, row] of previous) {
    if (!current.has(key)) changes.push({ kind: 'removed', row });
  }

  return changes;
}

export async function postDiscord(embed) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await fetch(DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'Proto[Typist] Updates',
        allowed_mentions: { parse: [] },
        embeds: [embed]
      })
    });

    if (response.ok) return;

    if (response.status === 429 && attempt < 3) {
      let retryAfter = 1;
      try {
        const body = await response.json();
        retryAfter = Number(body?.retry_after) || 1;
      } catch {}
      await sleep(Math.ceil(retryAfter * 1000));
      continue;
    }

    throw new Error(`Discord webhook failed: ${response.status} ${await response.text()}`);
  }
}

function formatKst(isoString) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).formatToParts(new Date(isoString));
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`;
}

function auxiliaryFields(row, detailNote, detectedAt) {
  const fields = [
    { name: '변경 감지 시각', value: `${formatKst(detectedAt)} KST`, inline: false },
    { name: '링크', value: `[🔗 상세 보기](${row.itemUrl || row.sourceUrl})`, inline: false }
  ];
  if (detailNote) fields.push({ name: '업데이트 내용', value: truncate(detailNote), inline: false });
  return fields;
}

function baseEmbed(row, title, detectedAt) {
  return {
    title: truncate(title, 250),
    url: row.itemUrl || row.sourceUrl,
    footer: { text: `Proto[Typist] · ${row.sourceLabel} Updates` },
    timestamp: detectedAt
  };
}

export function addedEmbed(row, detailNote = '', detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(row, `🆕 ${row.sourceEmoji} ${row.sourceLabel} 추가 · ${row.product}`, detectedAt),
    fields: [
      { name: '상태', value: truncate(row.status), inline: true },
      { name: '예상 고객 배송', value: truncate(row.expectedShipping || '—'), inline: true },
      ...auxiliaryFields(row, detailNote, detectedAt)
    ]
  };
}

export function changedEmbed(change, detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(change.row, `🔄 ${change.row.sourceEmoji} ${change.row.sourceLabel} 업데이트 · ${change.row.product}`, detectedAt),
    fields: [
      ...change.fields.map((field) => ({
        name: field.label,
        value: truncate(`이전: ${field.before}\n현재: ${field.after}`),
        inline: false
      })),
      ...auxiliaryFields(change.row, change.detailNote, detectedAt)
    ]
  };
}

export function removedEmbed(row, detailNote = '', detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(row, `➖ ${row.sourceEmoji} ${row.sourceLabel} 목록에서 제거 · ${row.product}`, detectedAt),
    description: 'Proto[Typist]의 현재 업데이트 보드에서 사라졌습니다.',
    fields: [
      { name: '마지막 상태', value: truncate(row.status), inline: true },
      { name: '예상 고객 배송', value: truncate(row.expectedShipping || '—'), inline: true },
      ...auxiliaryFields(row, detailNote, detectedAt)
    ]
  };
}

async function notify(change) {
  if (change.kind === 'added') return postDiscord(addedEmbed(change.row, change.detailNote));
  if (change.kind === 'changed') return postDiscord(changedEmbed(change));
  return postDiscord(removedEmbed(change.row, change.detailNote));
}

async function main() {
const rows = await fetchRows();
console.log(`Proto[Typist] total rows: ${rows.length}`);
console.log('Proto[Typist] counts:', JSON.stringify(sourceCounts(rows)));

const state = loadState();
const previousRows = Array.isArray(state?.rows) ? state.rows : [];

if (!state?.initialized || previousRows.length === 0) {
  console.log(`Baseline initialization: storing ${rows.length} Proto[Typist] rows without notifying.`);
  saveState(rows);
  process.exit(0);
}

const previousCounts = sourceCounts(previousRows);
const currentCounts = sourceCounts(rows);
for (const source of SOURCES) {
  const before = previousCounts[source.id] || 0;
  const after = currentCounts[source.id] || 0;
  if (before > 0 && after < Math.max(1, Math.floor(before * 0.5))) {
    throw new Error(`Proto[Typist] ${source.label} row count dropped unexpectedly: ${before} -> ${after}. State was not updated.`);
  }
}

const changes = diffRows(previousRows, rows);
console.log(`Proto[Typist] changes: ${changes.length}`);
for (const change of changes) {
  console.log(
    change.kind === 'changed' ? 'Changed:' : `${change.kind}:`,
    change.row.sourceLabel,
    change.row.product,
    change.kind === 'changed' ? change.fields.map((field) => field.label).join(', ') : ''
  );
}

if (changes.length === 0) {
  console.log('No Proto[Typist] state update needed.');
  process.exit(0);
}

if (!DISCORD_WEBHOOK_URL) {
  console.log('[discord] PROTOTYPIST_DISCORD_WEBHOOK_URL is not configured. Changes remain pending; state was not updated.');
  process.exit(0);
}

await addDetailNotes(changes).catch((error) => {
  console.warn(`Proto[Typist] detail lookups skipped: ${error?.message || error}`);
});
for (const change of changes) await notify(change);
saveState(rows);
console.log(`Sent ${changes.length} Proto[Typist] notification(s) and updated state.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
