import fs from 'node:fs';
import { chromium } from 'playwright';

const PAGE_URL = 'https://geonworks.kr/customhtml/release/release.html';
const STATE_PATH = 'geonworks-release-state.json';
const DISCORD_WEBHOOK_URL = (process.env.GEONWORKS_DISCORD_WEBHOOK_URL || '').trim();

const FIELD_LABELS = {
  manufacturer: '제조사',
  region: '지역',
  saleType: '판매 방식',
  type: '유형',
  status: '상태',
  fixed: '판매 결정',
  start: '판매 시작일',
  end: '판매 종료일'
};

const COMPARED_FIELDS = Object.keys(FIELD_LABELS);
const DATE_LABELS = new Set(['판매 시작일', '판매 종료일']);

function clean(value) {
  return String(value ?? '')
    .replace(/\r/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function truncate(value, maxLength = 1000) {
  const text = String(value ?? '')
    .replace(/\r/g, '')
    .split('\n')
    .map(clean)
    .filter(Boolean)
    .join('\n') || '—';
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

function normalizeKey(value) {
  return clean(value).toLocaleLowerCase('en-US');
}

function displayValue(label, value) {
  const text = clean(value) || '—';
  if (!DATE_LABELS.has(label)) return truncate(text);

  const dotted = text.match(/^(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})$/);
  if (dotted) {
    const [, year, month, day] = dotted;
    return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
  }

  const dashed = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (dashed) {
    const [, year, month, day] = dashed;
    return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
  }

  return truncate(text);
}

async function fetchRows() {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });

  try {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1600 },
      locale: 'ko-KR'
    });

    const dataRequests = new Set();
    const dataResponseBodies = [];
    page.on('response', (response) => {
      try {
        const url = new URL(response.url());
        if (/google|docs|sheets|gviz|csv/i.test(url.hostname + url.pathname + url.search)) {
          dataRequests.add(`${response.status()} ${url.toString()}`);
        }
        if (url.hostname === 'script.googleusercontent.com') {
          dataResponseBodies.push(
            response.text()
              .then((body) => ({ url: url.toString(), body: String(body || '').slice(0, 6000) }))
              .catch(() => null)
          );
        }
      } catch {}
    });
    page.on('requestfailed', (request) => {
      try {
        const url = new URL(request.url());
        if (/google|docs|sheets|gviz|csv/i.test(url.hostname + url.pathname + url.search)) {
          dataRequests.add(`FAILED ${url.toString()} · ${request.failure()?.errorText || 'unknown'}`);
        }
      } catch {}
    });

    await page.goto(PAGE_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForFunction(() => {
      const selectors = ['#tableBody tr', 'tbody tr', 'table tr'];
      return selectors.some((selector) => [...document.querySelectorAll(selector)]
        .some((tr) => tr.querySelectorAll('td').length >= 9));
    }, { timeout: 30000 });
    await page.waitForTimeout(700);

    const rows = await page.evaluate(() => {
      const cleanText = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
      const selectors = ['#tableBody tr', 'tbody tr', 'table tr'];
      let rowEls = [];

      for (const selector of selectors) {
        const candidates = [...document.querySelectorAll(selector)]
          .filter((tr) => tr.querySelectorAll('td').length >= 9);
        if (candidates.length) {
          rowEls = candidates;
          break;
        }
      }

      return rowEls
        .map((tr) => [...tr.querySelectorAll('td')].map((td) => cleanText(td.innerText)))
        .filter((cells) => cells.length >= 9 && cells[0])
        .map((cells) => ({
          product: cells[0],
          manufacturer: cells[1],
          region: cells[2],
          saleType: cells[3],
          type: cells[4],
          status: cells[5],
          fixed: cells[6],
          start: cells[7],
          end: cells[8]
        }));
    });

    if (dataRequests.size > 0) {
      console.log('GEONWORKS data requests:', JSON.stringify([...dataRequests].slice(0, 20), null, 2));
    }
    const responseBodies = (await Promise.all(dataResponseBodies)).filter(Boolean);
    if (responseBodies.length > 0) {
      console.log('GEONWORKS Release data response sample:', JSON.stringify(responseBodies[0], null, 2));
    }

    if (rows.length < 1) {
      throw new Error(`GEONWORKS Release page returned no rows: ${rows.length}`);
    }

    return rows;
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
  const nameCounts = new Map();
  for (const row of rows) {
    const name = normalizeKey(row.product);
    nameCounts.set(name, (nameCounts.get(name) || 0) + 1);
  }

  const map = new Map();
  for (const row of rows) {
    const name = normalizeKey(row.product);
    const key = nameCounts.get(name) > 1
      ? [name, row.manufacturer, row.region, row.saleType, row.start].map(normalizeKey).join('|')
      : name;
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

    const fields = COMPARED_FIELDS
      .filter((field) => clean(before[field]) !== clean(row[field]))
      .map((field) => ({
        field,
        label: FIELD_LABELS[field],
        before: before[field] || '—',
        after: row[field] || '—'
      }));

    if (fields.length) changes.push({ kind: 'changed', row, before, fields });
  }

  for (const [key, row] of previous) {
    if (!current.has(key)) changes.push({ kind: 'removed', row });
  }

  return changes;
}

async function postDiscord(embed) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await fetch(DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: 'GEONWORKS Alert',
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

function baseEmbed(title) {
  return {
    title: truncate(title, 250),
    url: PAGE_URL,
    footer: { text: 'GEONWORKS · Release 알림' },
    timestamp: new Date().toISOString()
  };
}

function addedEmbed(row) {
  return {
    ...baseEmbed(`📅 출시 일정 추가 · ${row.product}`),
    fields: [
      ['제조사', row.manufacturer],
      ['지역', row.region],
      ['판매 방식', row.saleType],
      ['유형', row.type],
      ['상태', row.status],
      ['판매 결정', row.fixed],
      ['판매 시작일', row.start],
      ['판매 종료일', row.end]
    ].map(([name, value]) => ({ name, value: displayValue(name, value), inline: true }))
  };
}

function changedEmbed(change) {
  return {
    ...baseEmbed(`🔄 출시 일정 변경 · ${change.row.product}`),
    description: `변경된 항목 **${change.fields.length}개**`,
    fields: change.fields.slice(0, 25).map((field) => ({
      name: field.label,
      value: truncate(`이전: ${displayValue(field.label, field.before)}\n현재: ${displayValue(field.label, field.after)}`),
      inline: false
    }))
  };
}

function removedEmbed(row) {
  return {
    ...baseEmbed(`➖ 출시 예정표에서 제거 · ${row.product}`),
    description: 'GEONWORKS Release의 현재 목록에서 사라졌습니다.',
    fields: [
      { name: '제조사', value: truncate(row.manufacturer), inline: true },
      { name: '판매 방식', value: truncate(row.saleType), inline: true },
      { name: '상태', value: truncate(row.status), inline: true },
      { name: '판매 시작일', value: displayValue('판매 시작일', row.start), inline: true },
      { name: '판매 종료일', value: displayValue('판매 종료일', row.end), inline: true }
    ]
  };
}

async function notify(change) {
  if (change.kind === 'added') return postDiscord(addedEmbed(change.row));
  if (change.kind === 'changed') return postDiscord(changedEmbed(change));
  return postDiscord(removedEmbed(change.row));
}

let rows = await fetchRows();
console.log(`GEONWORKS Release rows: ${rows.length}`);
console.log('GEONWORKS Release sample:', JSON.stringify(rows[0], null, 2));

const state = loadState();
const previousRows = Array.isArray(state?.rows) ? state.rows : [];

if (!state?.initialized || previousRows.length === 0) {
  console.log(`Baseline initialization: storing ${rows.length} GEONWORKS Release rows without notifying.`);
  saveState(rows);
  process.exit(0);
}

const validateRows = (candidateRows) => {
  if (candidateRows.length < Math.max(1, Math.floor(previousRows.length * 0.5))) {
    throw new Error(`GEONWORKS Release row count dropped unexpectedly: ${previousRows.length} -> ${candidateRows.length}. State was not updated.`);
  }
};
const removalSignature = (candidateChanges) => candidateChanges
  .filter((change) => change.kind === 'removed')
  .map((change) => JSON.stringify(change.row))
  .sort()
  .join('\n');

validateRows(rows);
let changes = diffRows(previousRows, rows);
const firstRemovalSignature = removalSignature(changes);
if (firstRemovalSignature) {
  console.warn('GEONWORKS Release removal detected; re-reading once before notifying.');
  const verificationRows = await fetchRows();
  validateRows(verificationRows);
  const verificationChanges = diffRows(previousRows, verificationRows);
  const secondRemovalSignature = removalSignature(verificationChanges);
  if (secondRemovalSignature && secondRemovalSignature !== firstRemovalSignature) {
    throw new Error('GEONWORKS Release removal set changed during verification. State was not updated.');
  }
  rows = verificationRows;
  changes = verificationChanges;
  console.log(secondRemovalSignature
    ? 'GEONWORKS Release removal confirmed by two consecutive reads.'
    : 'GEONWORKS Release removal disappeared on verification; using the verified second snapshot.');
}

console.log(`GEONWORKS Release changes: ${changes.length}`);

for (const change of changes) {
  if (change.kind === 'changed') {
    console.log('Changed:', change.row.product, change.fields.map((x) => x.label).join(', '));
  } else {
    console.log(`${change.kind}:`, change.row.product);
  }
}

if (changes.length === 0) {
  console.log('No GEONWORKS Release state update needed.');
  process.exit(0);
}

if (!DISCORD_WEBHOOK_URL) {
  console.log('[discord] GEONWORKS_DISCORD_WEBHOOK_URL is not configured. Changes remain pending; state was not updated.');
  process.exit(0);
}

for (const change of changes) {
  await notify(change);
}

saveState(rows);
console.log(`Sent ${changes.length} GEONWORKS Release notification(s) and updated state.`);
