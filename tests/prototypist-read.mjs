import { chromium } from 'playwright';

const pages = [
  {
    name: 'Keyboard',
    url: 'https://prototypist.notion.site/57d8169dada041f6b52b1d091e3e2c97?v=449c85bd9a29455fa8d1330e23680c5a'
  },
  {
    name: 'GMK',
    url: 'https://prototypist.notion.site/dfedac384a2b494c9137be7638b11e93?v=11769ac8c9764aafb907fe3d65fb0c50'
  },
  {
    name: 'Keyset',
    url: 'https://prototypist.notion.site/119c46c4a89140e98b295ed0102eb94e?v=a13cd09931964dd596b125ea0013f5c6'
  }
];

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } });

for (const target of pages) {
  const page = await context.newPage();
  console.log(`\n===== ${target.name} =====`);
  console.log(`URL: ${target.url}`);

  try {
    await page.goto(target.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(10000);

    console.log(`TITLE: ${await page.title()}`);

    const result = await page.evaluate(() => {
      const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
      const visible = (el) => {
        const r = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
      };

      const text = clean(document.body.innerText);
      const links = [...document.querySelectorAll('a')]
        .filter(visible)
        .map(a => ({ text: clean(a.innerText), href: a.href }))
        .filter(x => x.text)
        .slice(0, 80);

      const candidates = [...document.querySelectorAll('a, [role="button"], [role="link"]')]
        .filter(visible)
        .map(el => clean(el.innerText))
        .filter(t => t && t.length >= 3 && t.length <= 180)
        .filter((t, i, arr) => arr.indexOf(t) === i)
        .slice(0, 120);

      return {
        bodyText: text.slice(0, 12000),
        links,
        candidates,
        elementCounts: {
          anchors: document.querySelectorAll('a').length,
          buttons: document.querySelectorAll('[role="button"]').length,
          linksRole: document.querySelectorAll('[role="link"]').length
        }
      };
    });

    console.log('ELEMENT_COUNTS', JSON.stringify(result.elementCounts));
    console.log('VISIBLE_LINKS', JSON.stringify(result.links, null, 2));
    console.log('CANDIDATES', JSON.stringify(result.candidates, null, 2));
    console.log('BODY_TEXT_START');
    console.log(result.bodyText);
    console.log('BODY_TEXT_END');
  } catch (error) {
    console.error(`${target.name} ERROR`, error?.stack || error);
    process.exitCode = 1;
  } finally {
    await page.close();
  }
}

await browser.close();
