import { chromium } from 'playwright';

const name = process.env.SOURCE_NAME;
const url = process.env.SOURCE_URL;
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 2200, height: 1600 } });
try {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(12000);
  const result = await page.evaluate(() => ({
    title: document.title,
    anchors: [...document.querySelectorAll('a')].filter(a => (a.innerText || '').trim()).length,
    statuses: ['In Group Buy','In Manufacturing','Shipping to Prototypist','In Quality Control','Shipping to Customers','Completed'].filter(status => [...document.querySelectorAll('*')].some(el => (el.innerText || '').replace(/\s+/g,' ').trim() === status))
  }));
  console.log(name, JSON.stringify(result));
  if (result.title === 'Just a moment...' || result.anchors < 5 || result.statuses.length < 4) process.exitCode = 1;
} finally {
  await browser.close();
}
