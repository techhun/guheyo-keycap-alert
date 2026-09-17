import { chromium } from 'playwright';

const input = process.argv[2] || 'https://smartstore.naver.com/swagkey/products/12348949592';

function parseTarget(value) {
  const url = new URL(value);
  const match = url.pathname.match(/^\/([^/]+)\/products\/(\d+)\/?$/);
  if (!match) {
    throw new Error('SmartStore 상품 URL 형식이 아닙니다: https://smartstore.naver.com/{store}/products/{productNo}');
  }
  return {
    store: match[1],
    productNo: match[2],
    canonicalUrl: `https://smartstore.naver.com/${match[1]}/products/${match[2]}`
  };
}

function summarizeProduct(data) {
  const combinations = Array.isArray(data?.optionCombinations) ? data.optionCombinations : [];
  const simpleOptions = Array.isArray(data?.options) ? data.options : [];

  return {
    id: data?.id ?? null,
    productNo: data?.productNo ?? null,
    name: data?.name ?? null,
    statusType: data?.statusType ?? data?.productStatusType ?? null,
    stockQuantity: data?.stockQuantity ?? null,
    optionCombinationCount: combinations.length,
    optionCount: simpleOptions.length,
    optionCombinations: combinations.slice(0, 50).map((option) => ({
      id: option?.id ?? null,
      optionName1: option?.optionName1 ?? null,
      optionName2: option?.optionName2 ?? null,
      optionName3: option?.optionName3 ?? null,
      stockQuantity: option?.stockQuantity ?? null,
      usable: option?.usable ?? null
    }))
  };
}

const target = parseTarget(input);
const profileDir = `.restock-probe-profile`;

let context;
try {
  context = await chromium.launchPersistentContext(profileDir, {
    channel: 'chrome',
    headless: false,
    viewport: { width: 1280, height: 900 },
    locale: 'ko-KR'
  });
} catch (error) {
  console.error('[probe] Chrome 실행 실패:', error?.message || error);
  console.error('[probe] PC에 Chrome이 설치되어 있는지 확인하세요.');
  process.exit(1);
}

const page = context.pages()[0] || await context.newPage();
const captured = [];

page.on('response', async (response) => {
  const url = response.url();
  if (!/\/i\/v\d+\//.test(url)) return;
  if (!url.includes('smartstore.naver.com')) return;
  const record = {
    status: response.status(),
    url,
    contentType: response.headers()['content-type'] || ''
  };
  captured.push(record);
  console.log('[network]', JSON.stringify(record));
});

try {
  console.log(`[probe] open: ${target.canonicalUrl}`);
  const navigation = await page.goto(target.canonicalUrl, {
    waitUntil: 'domcontentloaded',
    timeout: 45000
  });

  console.log('[page]', JSON.stringify({
    status: navigation?.status() ?? null,
    url: page.url(),
    title: await page.title()
  }));

  await page.waitForTimeout(2500);

  const result = await page.evaluate(async ({ store, productNo }) => {
    async function getJson(url) {
      const response = await fetch(url, {
        credentials: 'include',
        headers: {
          accept: 'application/json, text/plain, */*',
          'cache-control': 'no-cache',
          pragma: 'no-cache'
        }
      });
      const text = await response.text();
      let json = null;
      try { json = JSON.parse(text); } catch {}
      return {
        status: response.status,
        contentType: response.headers.get('content-type'),
        json,
        preview: json ? null : text.replace(/\s+/g, ' ').slice(0, 180)
      };
    }

    const resolverUrl = `/i/v1/smart-stores?url=${encodeURIComponent(store)}`;
    const resolver = await getJson(resolverUrl);
    const channel = resolver.json?.channel ?? resolver.json;
    const channelUid = channel?.channelUid || channel?.channelId || null;

    let product = null;
    if (channelUid) {
      const productUrl = `/i/v2/channels/${encodeURIComponent(channelUid)}/products/${productNo}?withWindow=false`;
      product = await getJson(productUrl);
    }

    return {
      resolver: {
        status: resolver.status,
        contentType: resolver.contentType,
        channelUid,
        channelName: channel?.channelName ?? null,
        preview: resolver.preview
      },
      product
    };
  }, target);

  console.log('[resolver]', JSON.stringify(result.resolver, null, 2));

  if (!result.resolver.channelUid) {
    console.log('[result] CHANNEL_UID_NOT_FOUND');
    console.log('[captured]', JSON.stringify(captured, null, 2));
    process.exitCode = 2;
  } else if (!result.product) {
    console.log('[result] PRODUCT_API_NOT_CALLED');
    process.exitCode = 3;
  } else if (result.product.status !== 200 || !result.product.json) {
    console.log('[product]', JSON.stringify({
      status: result.product.status,
      contentType: result.product.contentType,
      preview: result.product.preview
    }, null, 2));
    console.log('[result] PRODUCT_API_FAILED');
    process.exitCode = 4;
  } else {
    console.log('[product]', JSON.stringify(summarizeProduct(result.product.json), null, 2));
    console.log('[result] OK');
  }

  console.log('[captured]', JSON.stringify(captured, null, 2));
  console.log('[probe] 브라우저는 15초 뒤 자동 종료됩니다.');
  await page.waitForTimeout(15000);
} catch (error) {
  console.error('[probe] 실패:', error?.stack || error);
  process.exitCode = 1;
} finally {
  await context.close();
}
