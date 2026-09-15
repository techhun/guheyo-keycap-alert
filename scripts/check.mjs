import { chromium } from 'playwright';
import fs from 'node:fs';
import crypto from 'node:crypto';

const MARKET_URL = process.env.GUHEYO_URL || 'https://guheyo.com/g/keyboard/sell?category=keycap';
const NTFY_TOPIC = (process.env.NTFY_TOPIC || '').trim();
const STATE_PATH = 'state.json';
const MAX_SEEN = 300;
const NTFY_CHUNK_BYTES = 2800;

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

function parseListing(text) {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim();
  const price = normalized.match(/([\d,]+\s*원)\s*$/)?.[1] || '';
  let title = normalized
    .replace(/^(?:방금 전|하루 전|\d+\s*(?:초|분|시간|일)\s*전)\s+/, '')
    .replace(/\s+키캡\s+[\d,]+\s*원\s*$/, '')
    .trim();
  if (!title) title = '새 키캡 매물';
  return { title, price, normalized };
}

function splitUtf8(text, maxBytes = NTFY_CHUNK_BYTES) {
  const chunks = [];
  let current = '';

  for (const char of String(text || '')) {
    const next = current + char;
    if (Buffer.byteLength(next, 'utf8') > maxBytes) {
      if (current) chunks.push(current);
      current = char;
    } else {
      current = next;
    }
  }

  if (current) chunks.push(current);
  return chunks.length ? chunks : [''];
}

async function fetchListingDetail(item) {
  const detailPage = await browser.newPage({
    viewport: { width: 1280, height: 1800 },
    locale: 'ko-KR',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'
  });

  try {
    await detailPage.goto(item.url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await detailPage.waitForTimeout(1200);

    const main = detailPage.locator('main');
    let text = '';

    if (await main.count()) {
      text = await main.first().innerText().catch(() => '');
    }
    if (!text.trim()) {
      text = await detailPage.locator('body').innerText().catch(() => '');
    }

    text = String(text || '')
      .replace(/\r/g, '')
      .replace(/[ \t]+\n/g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();

    console.log(`Detail text bytes for ${item.title}: ${Buffer.byteLength(text, 'utf8')}`);
    return text;
  } catch (error) {
    console.warn(`Could not load listing detail for ${item.url}:`, error?.message || error);
    return '';
  } finally {
    await detailPage.close();
  }
}

async function sendNotification(item) {
  const header = `${item.title}${item.price ? `\n${item.price}` : ''}`;
  const fullMessage = item.detail ? `${header}\n\n${item.detail}` : header;
  const chunks = splitUtf8(fullMessage);

  if (!NTFY_TOPIC) {
    console.log('[notify:dry-run]', fullMessage, item.url);
    return;
  }

  for (let i = 0; i < chunks.length; i += 1) {
    const response = await fetch('https://ntfy.sh', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        topic: NTFY_TOPIC,
        title: chunks.length > 1 ? `키캡 새 매물 (${i + 1}/${chunks.length})` : '키캡 새 매물',
        message: chunks[i],
        priority: 4,
        tags: ['shopping_cart'],
        click: item.url
      })
    });

    if (!response.ok) {
      throw new Error(`ntfy failed: ${response.status} ${await response.text()}`);
    }
  }
}

function saveState(ids) {
  fs.writeFileSync(
    STATE_PATH,
    JSON.stringify({
      initialized: true,
      updatedAt: new Date().toISOString(),
      seen: [...new Set(ids)].slice(0, MAX_SEEN)
    }, null, 2) + '\n'
  );
}

const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const page = await browser.newPage({
  viewport: { width: 1280, height: 1800 },
  locale: 'ko-KR',
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'
});

try {
  console.log('Opening:', MARKET_URL);
  await page.goto(MARKET_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });

  const offerLinks = page.locator('a[href*="/offer/"]');
  await offerLinks.first().waitFor({ state: 'attached', timeout: 20_000 });
  await page.waitForTimeout(1500);

  const rawLinks = await offerLinks.evaluateAll((nodes) =>
    nodes.map((a) => ({
      href: a.href,
      text: (a.innerText || a.textContent || '').replace(/\s+/g, ' ').trim()
    }))
  );

  const unique = new Map();
  for (const link of rawLinks) {
    if (!link.href || !link.text) continue;
    if (!/키캡/.test(link.text) || !/[\d,]+\s*원/.test(link.text)) continue;
    if (!unique.has(link.href)) unique.set(link.href, link);
  }

  const candidates = [...unique.values()];
  console.log(`Found ${candidates.length} keycap listings.`);
  console.log(JSON.stringify(candidates.slice(0, 8), null, 2));

  if (candidates.length === 0) {
    throw new Error('No keycap listings found. Guheyo page structure may have changed.');
  }

  const items = candidates.map((x) => {
    const parsed = parseListing(x.text);
    return {
      id: hash(x.href),
      url: x.href,
      title: parsed.title,
      price: parsed.price,
      summary: parsed.normalized,
      detail: ''
    };
  });

  const state = loadState();
  const seen = new Set(Array.isArray(state.seen) ? state.seen : []);

  if (!state.initialized || seen.size === 0) {
    console.log(`Baseline initialization: storing ${items.length} current listings without notifying.`);
    saveState(items.map((x) => x.id));
  } else {
    const fresh = items.filter((x) => !seen.has(x.id));
    console.log(`New listings: ${fresh.length}`);

    if (fresh.length === 0) {
      console.log('No state update needed.');
    } else {
      // 최신 목록의 위쪽부터 수집되므로 실제 등록 순서대로 알리기 위해 역순 전송한다.
      for (const item of fresh.slice(0, 10).reverse()) {
        console.log('New:', item.title, item.price, item.url);
        item.detail = await fetchListingDetail(item);
        await sendNotification(item);
      }

      saveState([...items.map((x) => x.id), ...seen]);
    }
  }
} finally {
  await browser.close();
}
