import { chromium } from 'playwright';
import fs from 'node:fs';
import crypto from 'node:crypto';

const MARKET_URL = process.env.GUHEYO_URL || 'https://guheyo.com/g/keyboard/sell';
const NTFY_TOPIC = (process.env.NTFY_TOPIC || '').trim();
const STATE_PATH = 'state.json';
const MAX_SEEN = 300;

function hash(value) {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 20);
}

function loadState() {
  try {
    return JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
  } catch {
    return { seen: [], initialized: false };
  }
}

function cleanLines(text) {
  return String(text || '')
    .split(/\n+/)
    .map((v) => v.trim())
    .filter(Boolean);
}

function pickTitle(text) {
  const ignored = new Set([
    '키보드', '키캡', '판매', '경매', '구매', '교환', '공동구매', '전체',
    '커스텀', '기성품', '아티산', '스위치', '기타', '공임', '팔로잉'
  ]);

  for (const line of cleanLines(text)) {
    if (ignored.has(line)) continue;
    if (/^(\d+\s*(초|분|시간|일)\s*전|방금 전)$/.test(line)) continue;
    if (/^[\d,]+\s*원$/.test(line)) continue;
    if (line.length >= 3) return line;
  }
  return cleanLines(text)[0] || '새 키캡 매물';
}

async function sendNotification(item) {
  const body = `${item.title}\n${item.summary}`.slice(0, 900);
  if (!NTFY_TOPIC) {
    console.log('[notify:dry-run]', body, item.url);
    return;
  }

  const endpoint = `https://ntfy.sh/${encodeURIComponent(NTFY_TOPIC)}`;
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Title': 'New keycap listing',
      'Priority': 'high',
      'Tags': 'shopping_cart',
      'Click': item.url
    },
    body
  });
  if (!response.ok) throw new Error(`ntfy failed: ${response.status} ${await response.text()}`);
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  viewport: { width: 1280, height: 1800 },
  locale: 'ko-KR',
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'
});

const interestingResponses = [];
page.on('response', async (response) => {
  const req = response.request();
  const type = req.resourceType();
  const ct = (response.headers()['content-type'] || '').toLowerCase();
  const url = response.url();
  if (['xhr', 'fetch'].includes(type) || ct.includes('json') || /api|graphql|supabase|firebase/i.test(url)) {
    interestingResponses.push({ status: response.status(), type, contentType: ct, url });
  }
});

try {
  console.log('Opening:', MARKET_URL);
  await page.goto(MARKET_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(6000);

  const keycap = page.getByText('키캡', { exact: true });
  if (await keycap.count()) {
    try {
      await keycap.first().click({ timeout: 10_000 });
      await page.waitForTimeout(3000);
    } catch (error) {
      console.log('Keycap click skipped:', error.message);
    }
  } else {
    console.log('No exact 키캡 control found; continuing with current page.');
  }

  console.log('Filtered URL:', page.url());

  const bodyText = await page.locator('body').innerText().catch(() => '');
  console.log('BODY TEXT (first 12000 chars):');
  console.log(bodyText.slice(0, 12000));

  console.log('INTERESTING RESPONSES:');
  console.log(JSON.stringify(interestingResponses.slice(-100), null, 2));

  const scriptSrcs = await page.locator('script[src]').evaluateAll((nodes) => nodes.map((s) => s.src));
  console.log('SCRIPT SRCS:');
  console.log(JSON.stringify(scriptSrcs.slice(-80), null, 2));

  const rawLinks = await page.locator('a[href]').evaluateAll((nodes) =>
    nodes.map((a) => ({
      href: a.href,
      text: (a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim()
    }))
  );

  const sameSite = rawLinks.filter((x) => {
    try {
      const u = new URL(x.href);
      return u.hostname === 'guheyo.com' || u.hostname.endsWith('.guheyo.com');
    } catch {
      return false;
    }
  });

  let candidates = sameSite.filter((x) =>
    x.text.length >= 4 &&
    (/[\d,]+\s*원/.test(x.text) || /(초|분|시간|일)\s*전/.test(x.text)) &&
    !/^홈$|^장터$|^검색$|^나$/.test(x.text)
  );

  const byKey = new Map();
  for (const item of candidates) {
    const key = `${item.href}::${item.text}`;
    if (!byKey.has(key)) byKey.set(key, item);
  }
  candidates = [...byKey.values()];

  console.log(`Found ${rawLinks.length} anchors, ${candidates.length} listing candidates.`);
  console.log('Candidate sample:');
  console.log(JSON.stringify(candidates.slice(0, 20), null, 2));

  if (candidates.length === 0) {
    console.log('Anchor sample for diagnostics:');
    console.log(JSON.stringify(sameSite.slice(0, 80), null, 2));
    throw new Error('No listing candidates found. Selector/heuristic needs adjustment.');
  }

  const items = candidates.map((x) => {
    const normalized = x.text.replace(/\s+/g, ' ').trim();
    const idSource = x.href && x.href !== page.url() ? x.href : normalized;
    return {
      id: hash(idSource),
      url: x.href || page.url(),
      title: pickTitle(x.text),
      summary: normalized.slice(0, 500)
    };
  });

  const state = loadState();
  const seen = new Set(Array.isArray(state.seen) ? state.seen : []);

  if (!state.initialized || seen.size === 0) {
    console.log(`Baseline initialization: storing ${items.length} current listings without notifying.`);
    fs.writeFileSync(STATE_PATH, JSON.stringify({
      initialized: true,
      updatedAt: new Date().toISOString(),
      seen: items.map((x) => x.id).slice(0, MAX_SEEN)
    }, null, 2) + '\n');
  } else {
    const fresh = items.filter((x) => !seen.has(x.id));
    console.log(`New listings: ${fresh.length}`);
    for (const item of fresh.slice(0, 10).reverse()) {
      console.log('New:', item.title, item.url);
      await sendNotification(item);
    }
    const nextSeen = [...items.map((x) => x.id), ...seen].slice(0, MAX_SEEN);
    fs.writeFileSync(STATE_PATH, JSON.stringify({
      initialized: true,
      updatedAt: new Date().toISOString(),
      seen: [...new Set(nextSeen)]
    }, null, 2) + '\n');
  }
} finally {
  await browser.close();
}
