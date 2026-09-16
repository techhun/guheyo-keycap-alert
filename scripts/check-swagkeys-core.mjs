import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const STATE_PATH = 'swg-state.json';
const DISCORD_WEBHOOK_URL = (process.env.SWG_DISCORD_WEBHOOK_URL || '').trim();
const ROADMAP_URL = 'https://swagkeys.notion.site/swg-keycap-roadmap';
const STATUS_URL = 'https://swagkeys.notion.site/3b5f75d536018064b051e6a663b41d35?v=c68f75d5360182a89e8588a1aec3a749';
const QUARTERS = ['Q1', 'Q2', 'Q3', 'Q4'];
const STAGES = [
  { key: 'groupBuy', label: '공제' },
  { key: 'waitingProduction', label: '생산 대기' },
  { key: 'inProduction', label: '생산중' },
  { key: 'shipping', label: '배송중' },
  { key: 'fulfilled', label: '예판분 발송 완료' },
  { key: 'inStock', label: '인스탁 판매중' }
];

const clean = (value) => String(value ?? '').replace(/\r/g, '').replace(/\s+/g, ' ').trim();
const cleanMultiline = (value) => String(value ?? '')
  .replace(/\r/g, '')
  .split('\n')
  .map(clean)
  .filter(Boolean)
  .join('\n');
const truncate = (value, maxLength = 1000) => {
  const text = cleanMultiline(value) || '—';
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1).trimEnd()}…`;
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const INVALID_ROADMAP_RE = /^(?:불러오는 중(?:\.{3})?|loading(?:\.{3})?|결과 없음|no results|문제 발생|다시 시도하기|something went wrong|try again)$/i;
const isInvalidRoadmapValue = (value) => INVALID_ROADMAP_RE.test(clean(value));
const quarterSnapshotIsValid = (quarters) => QUARTERS.every((quarter) => {
  const products = quarters?.[quarter];
  return Array.isArray(products)
    && products.length > 0
    && products.every((product) => clean(product) && !isInvalidRoadmapValue(product));
});

async function openWithRetry(context, url, label, extractor) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const page = await context.newPage();
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      if (/just a moment/i.test(await page.title())) throw new Error('blocked by the Notion challenge page');
      const result = await extractor(page);
      console.log(`SWAGKEYS ${label} read successfully on attempt ${attempt}.`);
      return result;
    } catch (error) {
      lastError = error;
      console.warn(`SWAGKEYS ${label} attempt ${attempt} failed: ${error?.message || error}`);
      console.warn(`SWAGKEYS ${label} diagnostics: title=${await page.title().catch(() => '')}; body=${clean((await page.locator('body').innerText({ timeout: 3000 }).catch(() => '')).slice(0, 300))}`);
      if (attempt < 3) await sleep(4000);
    } finally {
      await page.close();
    }
  }
  throw lastError;
}

async function extractRoadmap(page) {
  await page.waitForTimeout(4000);
  if (!/Swagkeys Keycap Roadmap/i.test(await page.title())) {
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 45000 });
  }
  await page.waitForFunction(() => {
    const note = document.querySelector('[role="note"]') || document.querySelector('.notion-callout-block');
    return /업데이트\s*\(/.test(note?.innerText || '');
  }, undefined, { timeout: 20000 });
  await page.waitForTimeout(500);

  const announcement = await page.evaluate(() => {
    const tidy = (value) => String(value ?? '').replace(/\r/g, '').trim();
    const note = document.querySelector('[role="note"]') || document.querySelector('.notion-callout-block');
    const lines = tidy(note?.innerText)
      .split('\n')
      .map((line) => line.replace(/\s+/g, ' ').trim())
      .filter((line) => line && line !== '📌');
    const headingIndex = lines.findIndex((line) => /^업데이트\s*\(/.test(line));
    return {
      heading: headingIndex >= 0 ? lines[headingIndex] : '',
      content: lines.slice(headingIndex >= 0 ? headingIndex + 1 : 0).join('\n')
    };
  });

  const quarters = { Q1: null, Q2: null, Q3: null, Q4: null };
  await page.mouse.move(900, 700);
  await page.evaluate(() => {
    const scroller = document.querySelector('.notion-scroller.vertical') || document.scrollingElement || document.documentElement;
    scroller.scrollTop = 0;
  });
  await page.waitForTimeout(700);

  // Notion virtualizes collection views. Even after every quarter has at least one
  // visible row, that can still be only a partial viewport. Scan the whole page
  // repeatedly and retain the longest clean list observed for each quarter.
  for (let step = 0; step < 72; step += 1) {
    const found = await page.evaluate(() => {
      const tidy = (value) => String(value ?? '').replace(/\r/g, '').trim();
      const invalid = /^(?:불러오는 중(?:\.{3})?|loading(?:\.{3})?|결과 없음|no results|문제 발생|다시 시도하기|something went wrong|try again)$/i;
      const result = {};

      for (const block of document.querySelectorAll('.notion-collection_view-block')) {
        const blockLines = tidy(block.innerText)
          .split('\n')
          .map((line) => line.replace(/\s+/g, ' ').trim())
          .filter(Boolean);
        const match = blockLines[0]?.match(/^[1-4]분기\s*\(Q([1-4])\)$/i);
        if (!match) continue;

        const bodyLines = blockLines.slice(1);
        if (bodyLines.some((line) => invalid.test(line))) {
          const retry = [...block.querySelectorAll('button, [role="button"]')]
            .find((element) => /다시 시도하기|try again/i.test((element.innerText || element.textContent || '').trim()));
          retry?.click();
          continue;
        }

        const products = [...new Set(bodyLines.filter((line) => line && !invalid.test(line)))];
        if (products.length > 0) result[`Q${match[1]}`] = products;
      }
      return result;
    });

    for (const [quarter, products] of Object.entries(found)) {
      if (quarters[quarter] === null || products.length > quarters[quarter].length) quarters[quarter] = products;
    }

    if ((step + 1) % 16 === 0) {
      await page.evaluate(() => {
        const scroller = document.querySelector('.notion-scroller.vertical') || document.scrollingElement || document.documentElement;
        scroller.scrollTop = 0;
      });
      await page.mouse.wheel(0, -10000);
    } else {
      await page.mouse.wheel(0, 900);
    }
    await page.waitForTimeout(650);
  }

  if (!quarterSnapshotIsValid(quarters)) {
    const missing = QUARTERS.filter((quarter) => !Array.isArray(quarters[quarter]) || quarters[quarter].length === 0);
    throw new Error(`quarter roadmap incomplete or contains Notion error placeholders: ${missing.length ? missing.join(', ') : 'invalid values'}`);
  }

  console.log(`SWAGKEYS quarter counts: ${Object.entries(quarters).map(([quarter, products]) => `${quarter}=${products.length}`).join(', ')}`);
  return { announcement, quarters };
}

async function extractRows(page) {
  await page.waitForFunction(() => document.querySelectorAll('.notion-table-view-row').length >= 10, undefined, { timeout: 25000 });
  await page.waitForTimeout(1000);
  return page.evaluate(() => {
    const tidy = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
    const progress = (cells, index) => {
      const value = Number(cells.get(index)?.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow'));
      return Number.isFinite(value) ? Math.round(value * 10) / 10 : 0;
    };
    return [...document.querySelectorAll('.notion-table-view-row')].map((row) => {
      const cells = new Map([...row.querySelectorAll('.notion-table-view-cell')]
        .map((cell) => [Number(cell.getAttribute('data-col-index')), cell]));
      return {
        product: tidy(cells.get(0)?.innerText),
        groupBuy: progress(cells, 1),
        waitingProduction: progress(cells, 2),
        inProduction: progress(cells, 3),
        shipping: progress(cells, 4),
        fulfilled: progress(cells, 5),
        inStock: progress(cells, 6),
        colorMatching: tidy(cells.get(7)?.innerText),
        eta: tidy(cells.get(8)?.innerText),
        currentStatus: tidy(cells.get(9)?.innerText)
      };
    }).filter((row) => row.product);
  });
}

async function fetchSnapshot(fallbackRows = []) {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const context = await browser.newContext({
    viewport: { width: 1800, height: 1400 },
    locale: 'ko-KR',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
  });
  try {
    const roadmap = await openWithRetry(context, ROADMAP_URL, 'roadmap', extractRoadmap);
    let rows;
    let statusFresh = true;
    try {
      rows = await openWithRetry(context, STATUS_URL, 'status table', extractRows);
    } catch (error) {
      if (!Array.isArray(fallbackRows) || fallbackRows.length < 10) throw error;
      rows = fallbackRows;
      statusFresh = false;
      console.warn(`SWAGKEYS status table unavailable after retries. Reusing ${rows.length} stored rows for this run.`);
    }
    if (!roadmap.announcement.heading || !roadmap.announcement.content) throw new Error('roadmap announcement was empty');
    if (!quarterSnapshotIsValid(roadmap.quarters)) throw new Error('quarter roadmap snapshot failed validation');
    if (Object.values(roadmap.quarters).flat().length < 5) throw new Error('quarter roadmap returned too few products');
    if (rows.length < 10) throw new Error(`status table returned too few rows: ${rows.length}`);
    return { announcement: roadmap.announcement, quarters: roadmap.quarters, rows, statusFresh };
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

function saveState(snapshot) {
  fs.writeFileSync(STATE_PATH, JSON.stringify({
    version: 1,
    initialized: true,
    updatedAt: new Date().toISOString(),
    announcement: snapshot.announcement,
    quarters: snapshot.quarters,
    rows: snapshot.rows
  }, null, 2) + '\n');
}

function rowMap(rows) {
  return new Map(rows.map((row) => [clean(row.product).toLowerCase(), row]));
}

function diffRows(previousRows, currentRows) {
  const previous = rowMap(previousRows);
  const current = rowMap(currentRows);
  const changes = [];
  const fields = [
    ...STAGES.map(({ key, label }) => ({ key, label: `${label} 진행률`, type: 'percent' })),
    { key: 'colorMatching', label: '컬러 매칭' },
    { key: 'eta', label: '배송 예정일' },
    { key: 'currentStatus', label: '현재 상태' }
  ];
  for (const [key, row] of current) {
    const before = previous.get(key);
    if (!before) {
      changes.push({ kind: 'added', row });
      continue;
    }
    const changedFields = fields
      .filter((field) => clean(before[field.key]) !== clean(row[field.key]))
      .map((field) => ({ ...field, before: before[field.key], after: row[field.key] }));
    if (changedFields.length) changes.push({ kind: 'changed', row, before, fields: changedFields });
  }
  for (const [key, row] of previous) {
    if (!current.has(key)) changes.push({ kind: 'removed', row });
  }
  return changes;
}

export function diffQuarters(previousQuarters = {}, currentQuarters = {}) {
  const locations = (quarters) => {
    const map = new Map();
    for (const quarter of QUARTERS) {
      for (const product of quarters[quarter] || []) {
        map.set(clean(product).toLowerCase(), { product: clean(product), quarter });
      }
    }
    return map;
  };
  const previous = locations(previousQuarters);
  const current = locations(currentQuarters);
  const result = { moved: [], added: [], removed: [] };
  for (const [key, item] of current) {
    const before = previous.get(key);
    if (!before) result.added.push(item);
    else if (before.quarter !== item.quarter) result.moved.push({ product: item.product, before: before.quarter, after: item.quarter });
  }
  for (const [key, item] of previous) {
    if (!current.has(key)) result.removed.push(item);
  }
  return result;
}

function hasQuarterChanges(changes) {
  return changes.moved.length > 0 || changes.added.length > 0 || changes.removed.length > 0;
}

function formatKst(isoString) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).formatToParts(new Date(isoString));
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`;
}

function displayPercent(value) {
  return `${Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 1 })}%`;
}
function auxiliaryFields(url, detectedAt) {
  return [
    { name: '변경 감지 시각', value: `${formatKst(detectedAt)} KST`, inline: false },
    { name: '링크', value: `[🔗 상세 보기](${url})`, inline: false }
  ];
}
function baseEmbed(title, url, detectedAt) {
  return { title: truncate(title, 250), url, footer: { text: 'SWAGKEYS · Keycap Roadmap' }, timestamp: detectedAt };
}

export function announcementEmbed(announcement, detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(`📌 SWAGKEYS 로드맵 · ${announcement.heading}`, ROADMAP_URL, detectedAt),
    fields: [
      { name: '업데이트 내용', value: truncate(announcement.content), inline: false },
      ...auxiliaryFields(ROADMAP_URL, detectedAt)
    ]
  };
}

export function quartersEmbed(changes, detectedAt = new Date().toISOString()) {
  const fields = [];
  if (changes.moved.length) fields.push({ name: '분기 이동', value: truncate(changes.moved.map((item) => `• ${item.product}: ${item.before} → ${item.after}`).join('\n')), inline: false });
  if (changes.added.length) fields.push({ name: '신규 배치', value: truncate(changes.added.map((item) => `• ${item.product} → ${item.quarter}`).join('\n')), inline: false });
  if (changes.removed.length) fields.push({ name: '로드맵 제외', value: truncate(changes.removed.map((item) => `• ${item.product} (기존 ${item.quarter})`).join('\n')), inline: false });
  return { ...baseEmbed('🗺️ SWAGKEYS 분기별 로드맵 업데이트', ROADMAP_URL, detectedAt), fields: [...fields, ...auxiliaryFields(ROADMAP_URL, detectedAt)] };
}

export function addedEmbed(row, detectedAt = new Date().toISOString()) {
  const progress = STAGES.map(({ key, label }) => `${label}: ${displayPercent(row[key])}`).join('\n');
  return {
    ...baseEmbed(`🆕 SWAGKEYS 제품 추가 · ${row.product}`, STATUS_URL, detectedAt),
    fields: [
      { name: '현재 상태', value: truncate(row.currentStatus), inline: false },
      { name: '배송 예정일', value: truncate(row.eta || '—'), inline: true },
      { name: '컬러 매칭', value: truncate(row.colorMatching || '—'), inline: true },
      { name: '단계별 진행률', value: progress, inline: false },
      ...auxiliaryFields(STATUS_URL, detectedAt)
    ]
  };
}

export function changedEmbed(change, detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(`🔄 SWAGKEYS 진행 업데이트 · ${change.row.product}`, STATUS_URL, detectedAt),
    fields: [
      ...change.fields.map((field) => ({
        name: field.label,
        value: truncate(`이전: ${field.type === 'percent' ? displayPercent(field.before) : field.before || '—'}\n현재: ${field.type === 'percent' ? displayPercent(field.after) : field.after || '—'}`),
        inline: false
      })),
      ...auxiliaryFields(STATUS_URL, detectedAt)
    ]
  };
}

export function removedEmbed(row, detectedAt = new Date().toISOString()) {
  return {
    ...baseEmbed(`➖ SWAGKEYS 로드맵에서 제거 · ${row.product}`, STATUS_URL, detectedAt),
    description: 'SWAGKEYS의 현재 키캡 생산 진행 상황 표에서 사라졌습니다.',
    fields: [
      { name: '마지막 상태', value: truncate(row.currentStatus || '—'), inline: false },
      { name: '마지막 배송 예정일', value: truncate(row.eta || '—'), inline: true },
      ...auxiliaryFields(STATUS_URL, detectedAt)
    ]
  };
}

async function postDiscord(embed) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await fetch(DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'SWAGKEYS Updates', allowed_mentions: { parse: [] }, embeds: [embed] })
    });
    if (response.ok) return;
    if (response.status === 429 && attempt < 3) {
      let retryAfter = 1;
      try { retryAfter = Number((await response.json())?.retry_after) || 1; } catch {}
      await sleep(Math.ceil(retryAfter * 1000));
      continue;
    }
    throw new Error(`Discord webhook failed: ${response.status} ${await response.text()}`);
  }
}

async function main() {
  const state = loadState();
  const previousRows = Array.isArray(state?.rows) ? state.rows : [];
  let snapshot = await fetchSnapshot(previousRows);
  console.log(`SWAGKEYS announcement: ${snapshot.announcement.heading}`);
  console.log(`SWAGKEYS status rows: ${snapshot.rows.length}${snapshot.statusFresh ? '' : ' (stored fallback)'}`);

  if (!state?.initialized || previousRows.length === 0) {
    console.log(`Baseline initialization: storing ${snapshot.rows.length} SWAGKEYS rows without notifying.`);
    saveState(snapshot);
    return;
  }

  if (!quarterSnapshotIsValid(state.quarters)) {
    console.warn('Stored SWAGKEYS quarter roadmap was incomplete or polluted by Notion error placeholders. Replacing it with a verified snapshot without notifying.');
    saveState(snapshot);
    return;
  }

  const validateRows = (candidateSnapshot) => {
    if (candidateSnapshot.rows.length < Math.max(8, Math.floor(previousRows.length * 0.5))) {
      throw new Error(`SWAGKEYS row count dropped unexpectedly: ${previousRows.length} -> ${candidateSnapshot.rows.length}. State was not updated.`);
    }
  };
  const removalSignature = (candidateQuarterChanges, candidateChanges) => [
    ...candidateQuarterChanges.removed.map((item) => `roadmap|${clean(item.product).toLowerCase()}|${item.quarter}`),
    ...candidateChanges
      .filter((change) => change.kind === 'removed')
      .map((change) => `status|${clean(change.row.product).toLowerCase()}`)
  ].sort().join('\n');
  const calculateChanges = (candidateSnapshot) => {
    const announcementChanged = clean(state.announcement?.heading) !== clean(candidateSnapshot.announcement.heading)
      || clean(state.announcement?.content) !== clean(candidateSnapshot.announcement.content);
    const quarterChanges = diffQuarters(state.quarters, candidateSnapshot.quarters);
    const changes = diffRows(previousRows, candidateSnapshot.rows);
    return { announcementChanged, quarterChanges, quartersChanged: hasQuarterChanges(quarterChanges), changes };
  };

  validateRows(snapshot);
  let calculated = calculateChanges(snapshot);
  const firstRemovalSignature = removalSignature(calculated.quarterChanges, calculated.changes);
  if (firstRemovalSignature) {
    console.warn('SWAGKEYS removal detected; re-reading once before notifying.');
    const verificationSnapshot = await fetchSnapshot(previousRows);
    validateRows(verificationSnapshot);
    const verificationCalculated = calculateChanges(verificationSnapshot);
    const secondRemovalSignature = removalSignature(verificationCalculated.quarterChanges, verificationCalculated.changes);
    if (secondRemovalSignature && secondRemovalSignature !== firstRemovalSignature) {
      throw new Error('SWAGKEYS removal set changed during verification. State was not updated.');
    }
    snapshot = verificationSnapshot;
    calculated = verificationCalculated;
    console.log(secondRemovalSignature
      ? 'SWAGKEYS removal confirmed by two consecutive reads.'
      : 'SWAGKEYS removal disappeared on verification; using the verified second snapshot.');
  }

  let { announcementChanged, quarterChanges, quartersChanged, changes } = calculated;
  console.log(`SWAGKEYS announcement changed: ${announcementChanged}`);
  console.log(`SWAGKEYS quarter roadmap changed: ${quartersChanged}`);
  console.log(`SWAGKEYS product changes: ${changes.length}`);

  if (!announcementChanged && !quartersChanged && changes.length === 0) {
    console.log('No SWAGKEYS state update needed.');
    return;
  }
  if (!DISCORD_WEBHOOK_URL) {
    console.log('[discord] SWAGKEYS_DISCORD_WEBHOOK_URL/SWG_DISCORD_WEBHOOK_URL is not configured. Changes remain pending; state was not updated.');
    return;
  }

  if (announcementChanged) await postDiscord(announcementEmbed(snapshot.announcement));
  if (quartersChanged) await postDiscord(quartersEmbed(quarterChanges));
  for (const change of changes) {
    if (change.kind === 'added') await postDiscord(addedEmbed(change.row));
    else if (change.kind === 'changed') await postDiscord(changedEmbed(change));
    else await postDiscord(removedEmbed(change.row));
  }
  saveState(snapshot);
  console.log(`Sent ${changes.length + Number(announcementChanged) + Number(quartersChanged)} SWAGKEYS notification(s) and updated state.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
