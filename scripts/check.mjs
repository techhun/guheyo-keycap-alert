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

function normalizeDetail(text) {
  return String(text || '')
    .replace(/\r/g, '')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

async function inspectDetailPage(url) {
  const debugPage = await browser.newPage({ locale: 'ko-KR' });
  const jsonResponses = [];
  debugPage.on('response', async (response) => {
    try {
      const ct = response.headers()['content-type'] || '';
      if (!ct.includes('application/json')) return;
      const u = response.url();
      if (!u.includes('guheyo.com')) return;
      const text = await response.text();
      jsonResponses.push({ url: u, text: text.slice(0, 4000) });
    } catch {}
  });

  try {
    await debugPage.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await debugPage.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
    await debugPage.waitForTimeout(2500);

    const data = await debugPage.evaluate(() => ({
      title: document.title,
      body: (document.body?.innerText || '').slice(0, 5000),
      metas: [...document.querySelectorAll('meta')]
        .map((m) => ({
          name: m.getAttribute('name'),
          property: m.getAttribute('property'),
          content: m.getAttribute('content')
        }))
        .filter((x) => x.content),
      jsonLd: [...document.querySelectorAll('script[type="application/ld+json"]')]
        .map((s) => s.textContent || '')
        .filter(Boolean),
      nextData: document.querySelector('#__NEXT_DATA__')?.textContent || '',
      scripts: [...document.scripts]
        .map((s) => s.textContent || '')
        .filter((t) => /description|content|offer|price|keycap/i.test(t))
        .map((t) => t.slice(0, 4000))
        .slice(0, 8)
    }));

    console.log('DETAIL DEBUG START');
    console.log(JSON.stringify({ ...data, jsonResponses }, null, 2));
    console.log('DETAIL DEBUG END');
  } finally {
    await debugPage.close();
  }
}

async function fetchListingDetail(item) {
  const detailPage = await browser.newPage({
    viewport: { width: 1280, height: 1800 },
    locale: 'ko-KR',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36'
  });

  try {
    await detailPage.goto(item.url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await detailPage.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
    await detailPage.waitForTimeout(2000);

    const candidates = await detailPage.evaluate(() => {
      const selectors = ['main', 'article', '[role="main"]', 'body'];
      const texts = [];
      for (const selector of selectors) {
        for (const el of document.querySelectorAll(selector)) {
          const text = (el.innerText || el.textContent || '').trim();
          if (text) texts.push({ selector, text });
        }
      }
      return texts;
    });

    const normalized = candidates
      .map((x) => ({ ...x, text: normalizeDetail(x.text) }))
      .filter((x) => x.text)
      .sort((a, b) => Buffer.byteLength(b.text, 'utf8') - Buffer.byteLength(a.text, 'utf8'));

    const best = normalized[0] || { selector: 'none', text: '' };
    console.log(`Detail source for ${item.title}: ${best.selector}, ${Buffer.byteLength(best.text, 'utf8')} bytes`);
    return best.text;
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
      headers: { 'Content-Type': 'application/json' },
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

  if (process.env.GITHUB_EVENT_NAME === 'push') {
    await inspectDetailPage(candidates[0].href);
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
