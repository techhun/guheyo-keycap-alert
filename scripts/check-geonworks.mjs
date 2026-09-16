import fs from 'node:fs';

const PAGE_URL = 'https://geonworks.kr/customhtml/GB_schedule.html';
const SHEET_URL = 'https://docs.google.com/spreadsheets/d/1i9SK14aWxpElXrjMinidKjVCXDi1XXBQSZJVB7ocbJk/gviz/tq?tqx=out:json&sheet=GB%20Schedule';
const STATE_PATH = 'geonworks-state.json';
const DISCORD_WEBHOOK_URL = (process.env.GEONWORKS_DISCORD_WEBHOOK_URL || '').trim();

const FIELD_LABELS = {
  gbStart: 'GB 시작일',
  eta: '예상 발송일',
  type: '분류',
  manufacturer: '제조사',
  status: '상태',
  update: '갱신 일자',
  note: '비고'
};

const COMPARED_FIELDS = Object.keys(FIELD_LABELS);

function clean(value) {
  return String(value ?? '')
    .replace(/\r/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function truncate(value, maxLength = 1000) {
  const text = clean(value) || '—';
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

function normalizeKey(value) {
  return clean(value).toLocaleLowerCase('en-US');
}

function cellValue(row, index) {
  const cell = row?.c?.[index];
  if (!cell) return '';
  if (cell.f != null) return clean(cell.f);
  if (cell.v != null) return clean(cell.v);
  return '';
}

function parseGviz(text) {
  const match = String(text || '').match(/google\.visualization\.Query\.setResponse\((\{[\s\S]*\})\);?\s*$/);
  if (!match) throw new Error('Could not parse Google Sheets GViz response.');

  const payload = JSON.parse(match[1]);
  if (payload.status === 'error') {
    throw new Error(`Google Sheets GViz error: ${JSON.stringify(payload.errors || payload)}`);
  }

  const rows = payload?.table?.rows;
  if (!Array.isArray(rows)) throw new Error('Google Sheets response does not contain rows.');

  return rows
    .map((row) => ({
      product: cellValue(row, 0),
      gbStart: cellValue(row, 1),
      eta: cellValue(row, 2),
      type: cellValue(row, 3),
      manufacturer: cellValue(row, 4),
      status: cellValue(row, 5),
      update: cellValue(row, 6),
      note: cellValue(row, 7) || '—'
    }))
    .filter((row) => row.product);
}

async function fetchRows() {
  const response = await fetch(SHEET_URL, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (compatible; keyboard-alert/1.0)',
      'Cache-Control': 'no-cache'
    }
  });

  if (!response.ok) {
    throw new Error(`GEONWORKS sheet fetch failed: ${response.status} ${response.statusText}`);
  }

  const rows = parseGviz(await response.text());
  if (rows.length < 5) {
    throw new Error(`GEONWORKS sheet returned suspiciously few rows: ${rows.length}`);
  }

  return rows;
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
      ? `${name}|${normalizeKey(row.manufacturer)}|${normalizeKey(row.gbStart)}`
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
        username: 'Keyboard Alert',
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
    footer: { text: 'GEONWORKS · GB Schedule 알림' },
    timestamp: new Date().toISOString()
  };
}

function addedEmbed(row) {
  return {
    ...baseEmbed(`🆕 신규 GB · ${row.product}`),
    fields: [
      ['GB 시작일', row.gbStart],
      ['예상 발송일', row.eta],
      ['분류', row.type],
      ['제조사', row.manufacturer],
      ['상태', row.status],
      ['갱신 일자', row.update],
      ['비고', row.note]
    ].map(([name, value]) => ({ name, value: truncate(value), inline: !['비고'].includes(name) }))
  };
}

function changedEmbed(change) {
  return {
    ...baseEmbed(`🔄 GB 업데이트 · ${change.row.product}`),
    description: `변경된 항목 **${change.fields.length}개**`,
    fields: change.fields.slice(0, 25).map((field) => ({
      name: field.label,
      value: truncate(`이전: ${field.before}\n현재: ${field.after}`),
      inline: false
    }))
  };
}

function removedEmbed(row) {
  return {
    ...baseEmbed(`➖ GB 목록에서 제거 · ${row.product}`),
    description: 'GEONWORKS GB Schedule의 현재 목록에서 사라졌습니다.',
    fields: [
      { name: '마지막 상태', value: truncate(row.status), inline: true },
      { name: '예상 발송일', value: truncate(row.eta), inline: true },
      { name: '마지막 갱신', value: truncate(row.update), inline: true },
      { name: '비고', value: truncate(row.note), inline: false }
    ]
  };
}

async function notify(change) {
  if (change.kind === 'added') return postDiscord(addedEmbed(change.row));
  if (change.kind === 'changed') return postDiscord(changedEmbed(change));
  return postDiscord(removedEmbed(change.row));
}

const rows = await fetchRows();
console.log(`GEONWORKS GB rows: ${rows.length}`);

const state = loadState();
const previousRows = Array.isArray(state?.rows) ? state.rows : [];

if (!state?.initialized || previousRows.length === 0) {
  console.log(`Baseline initialization: storing ${rows.length} GEONWORKS rows without notifying.`);
  saveState(rows);
  process.exit(0);
}

if (rows.length < Math.max(5, Math.floor(previousRows.length * 0.5))) {
  throw new Error(`GEONWORKS row count dropped unexpectedly: ${previousRows.length} -> ${rows.length}. State was not updated.`);
}

const changes = diffRows(previousRows, rows);
console.log(`GEONWORKS changes: ${changes.length}`);

for (const change of changes) {
  if (change.kind === 'changed') {
    console.log('Changed:', change.row.product, change.fields.map((x) => x.label).join(', '));
  } else {
    console.log(`${change.kind}:`, change.row.product);
  }
}

if (changes.length === 0) {
  console.log('No GEONWORKS state update needed.');
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
console.log(`Sent ${changes.length} GEONWORKS notification(s) and updated state.`);
