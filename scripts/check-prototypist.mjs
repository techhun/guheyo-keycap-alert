import fs from 'node:fs';
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
  {
    id: 'keyboard',
    label: 'Keyboard',
    emoji: '⌨️',
    url: 'https://prototypist.notion.site/57d8169dada041f6b52b1d091e3e2c97?v=449c85bd9a29455fa8d1330e23680c5a'
  },
  {
    id: 'gmk',
    label: 'GMK',
    emoji: '🇩🇪',
    url: 'https://prototypist.notion.site/dfedac384a2b494c9137be7638b11e93?v=11769ac8c9764aafb907fe3d65fb0c50'
  },
  {
    id: 'keyset',
    label: 'Keyset',
    emoji: '🧢',
    url: 'https://prototypist.notion.site/119c46c4a89140e98b295ed0102eb94e?v=a13cd09931964dd596b125ea0013f5c6'
  }
];

function clean(value) {
  return String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();
}

function truncate(value, maxLength = 1000) {
  const text = clean(value) || '—';
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

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
  const patterns = [
    /\s+(Late\s+Q[1-4](?:\s*-\s*Q[1-4])?\s+\d{4})$/i,
    /\s+(Q[1-4](?:\s*-\s*Q[1-4])?\s+\d{4})$/i,
    /\s+(Q[1-4]-Q[1-4]\s+\d{4})$/i,
    /\s+(TBC)$/i
  ];

  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match) {
      return {
        product: clean(text.slice(0, match.index)),
        expectedShipping: clean(match[1])
      };
    }
  }

  return { product: text, expectedShipping: '' };
}

async function fetchSource(browser, source) {
  const page = await browser.newPage({
    viewport: { width: 2200, height: 1600 },
    locale: 'en-GB',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'
  });

  try {
    await page.goto(source.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForFunction((statuses) => statuses.every((status) =>
      [...document.querySelectorAll('[role="button"]')]
        .some((el) => (el.innerText || '').replace(/\s+/g, ' ').trim() === status)
    ), STATUSES, { timeout: 30000 });
    await page.waitForFunction(() => [...document.querySelectorAll('a')]
      .filter((a) => /prototypist\.notion\.site\//.test(a.href || ''))
      .some((a) => (a.innerText || '').trim() && !/#main$/.test(a.href || '')),
    { timeout: 30000 });
    await page.waitForTimeout(5000);

    const rawRows = await page.evaluate((statuses) => {
      const cleanText = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();

      const headerButtons = [...document.querySelectorAll('[role="button"]')]
        .map((el) => ({ el, text: cleanText(el.innerText) }))
        .filter(({ text }) => statuses.includes(text));

      const headers = statuses.map((status) => {
        const found = headerButtons.find(({ text }) => text === status)?.el;
        if (!found) return null;
        const rect = found.getBoundingClientRect();
        return { status, centerX: rect.left + rect.width / 2 };
      }).filter(Boolean);

      const currentPath = location.pathname.replace(/\/$/, '');
      const anchors = [...document.querySelectorAll('a')]
        .map((a) => {
          const text = cleanText(a.innerText);
          const href = a.href || '';
          const rect = a.getBoundingClientRect();
          return {
            text,
            href,
            centerX: rect.left + rect.width / 2,
            width: rect.width,
            height: rect.height
          };
        })
        .filter((row) => row.text && row.width > 0 && row.height > 0)
        .filter((row) => /prototypist\.notion\.site\//.test(row.href))
        .filter((row) => {
          try {
            const u = new URL(row.href);
            return u.pathname.replace(/\/$/, '') !== currentPath && !u.hash;
          } catch {
            return false;
          }
        });

      return anchors.map((row) => {
        let nearest = null;
        for (const header of headers) {
          const distance = Math.abs(row.centerX - header.centerX);
          if (!nearest || distance < nearest.distance) nearest = { ...header, distance };
        }
        return {
          text: row.text,
          href: row.href,
          status: nearest?.status || '',
          statusDistance: nearest?.distance ?? null
        };
      });
    }, STATUSES);

    const deduped = new Map();
    for (const raw of rawRows) {
      const itemUrl = normalizeUrl(raw.href);
      const { product, expectedShipping } = parseCardText(raw.text);
      if (!product || !itemUrl || !STATUSES.includes(raw.status)) continue;
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
    if (rows.length < 1) throw new Error(`${source.label} returned no product rows.`);

    const invalidStatusCount = rows.filter((row) => !STATUSES.includes(row.status)).length;
    if (invalidStatusCount) throw new Error(`${source.label} has ${invalidStatusCount} rows with invalid status.`);

    console.log(`Proto[Typist] ${source.label} rows: ${rows.length}`);
    console.log(`Proto[Typist] ${source.label} sample:`, JSON.stringify(rows.slice(0, 3), null, 2));
    return rows;
  } finally {
    await page.close();
  }
}

async function fetchRows() {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const all = [];
    for (const source of SOURCES) all.push(...await fetchSource(browser, source));
    return all;
  } finally {
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
    const key = normalizeUrl(row.itemUrl) || `${row.sourceId}|${clean(row.product).toLowerCase()}`;
    map.set(key, row);
  }
  return map;
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

function sourceCounts(rows) {
  return Object.fromEntries(SOURCES.map((source) => [
    source.id,
    rows.filter((row) => row.sourceId === source.id).length
  ]));
}

async function postDiscord(embed) {
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
      await new Promise((resolve) => setTimeout(resolve, Math.ceil(retryAfter * 1000)));
      continue;
    }

    throw new Error(`Discord webhook failed: ${response.status} ${await response.text()}`);
  }
}

function baseEmbed(row, title) {
  return {
    title: truncate(title, 250),
    url: row.itemUrl || row.sourceUrl,
    footer: { text: `Proto[Typist] · ${row.sourceLabel} Updates` },
    timestamp: new Date().toISOString()
  };
}

function addedEmbed(row) {
  return {
    ...baseEmbed(row, `🆕 ${row.sourceEmoji} ${row.sourceLabel} 추가 · ${row.product}`),
    fields: [
      { name: '상태', value: truncate(row.status), inline: true },
      { name: '예상 고객 배송', value: truncate(row.expectedShipping || '—'), inline: true }
    ]
  };
}

function changedEmbed(change) {
  return {
    ...baseEmbed(change.row, `🔄 ${change.row.sourceEmoji} ${change.row.sourceLabel} 업데이트 · ${change.row.product}`),
    fields: change.fields.map((field) => ({
      name: field.label,
      value: truncate(`이전: ${field.before}\n현재: ${field.after}`),
      inline: false
    }))
  };
}

function removedEmbed(row) {
  return {
    ...baseEmbed(row, `➖ ${row.sourceEmoji} ${row.sourceLabel} 목록에서 제거 · ${row.product}`),
    description: 'Proto[Typist]의 현재 업데이트 보드에서 사라졌습니다.',
    fields: [
      { name: '마지막 상태', value: truncate(row.status), inline: true },
      { name: '예상 고객 배송', value: truncate(row.expectedShipping || '—'), inline: true }
    ]
  };
}

async function notify(change) {
  if (change.kind === 'added') return postDiscord(addedEmbed(change.row));
  if (change.kind === 'changed') return postDiscord(changedEmbed(change));
  return postDiscord(removedEmbed(change.row));
}

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
  if (change.kind === 'changed') {
    console.log('Changed:', change.row.sourceLabel, change.row.product, change.fields.map((x) => x.label).join(', '));
  } else {
    console.log(`${change.kind}:`, change.row.sourceLabel, change.row.product);
  }
}

if (changes.length === 0) {
  console.log('No Proto[Typist] state update needed.');
  process.exit(0);
}

if (!DISCORD_WEBHOOK_URL) {
  console.log('[discord] PROTOTYPIST_DISCORD_WEBHOOK_URL is not configured. Changes remain pending; state was not updated.');
  process.exit(0);
}

for (const change of changes) await notify(change);

saveState(rows);
console.log(`Sent ${changes.length} Proto[Typist] notification(s) and updated state.`);
