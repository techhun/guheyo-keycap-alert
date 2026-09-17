import { chromium } from 'playwright';

const input = process.argv[2] || 'https://smartstore.naver.com/swagkey/products/12348949592';

function parseTarget(value) {
  const url = new URL(value);
  const allowedHosts = new Set(['smartstore.naver.com', 'm.smartstore.naver.com']);
  if (!allowedHosts.has(url.hostname)) {
    throw new Error('SmartStore 호스트가 아닙니다: smartstore.naver.com 또는 m.smartstore.naver.com');
  }

  const match = url.pathname.match(/^\/([^/]+)\/products\/(\d+)\/?$/);
  if (!match) {
    throw new Error('SmartStore 상품 URL 형식이 아닙니다: https://{m.}smartstore.naver.com/{store}/products/{productNo}');
  }

  return {
    store: match[1],
    productNo: match[2],
    origin: url.origin,
    canonicalUrl: `${url.origin}/${match[1]}/products/${match[2]}`
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

async function fetchProductInPage(page, channelUid, productNo) {
  return page.evaluate(async ({ channelUid, productNo }) => {
    const url = `/i/v2/channels/${encodeURIComponent(channelUid)}/products/${productNo}?withWindow=false`;
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
  }, { channelUid, productNo });
}

const target = parseTarget(input);
const profileDir = process.env.RESTOCK_PROBE_USER_DATA_DIR || '.restock-probe-profile';
const chromeProfile = process.env.RESTOCK_PROBE_PROFILE_DIRECTORY || '';
const launchArgs = chromeProfile ? [`--profile-directory=${chromeProfile}`] : [];

console.log('[probe-profile]', JSON.stringify({
  userDataDir: profileDir,
  profileDirectory: chromeProfile || '(temporary probe profile)'
}));

let context;
try {
  context = await chromium.launchPersistentContext(profileDir, {
    channel: 'chrome',
    headless: false,
    viewport: { width: 1280, height: 900 },
    locale: 'ko-KR',
    args: launchArgs
  });
} catch (error) {
  console.error('[probe] Chrome 실행 실패:', error?.message || error);
  if (process.env.RESTOCK_PROBE_USER_DATA_DIR) {
    console.error('[probe] 실제 Chrome 프로필을 사용하는 경우 실행 중인 Chrome을 모두 종료한 뒤 다시 시도하세요.');
    console.error('[probe] 작업 관리자에 chrome.exe가 남아 있으면 그것도 종료해야 할 수 있습니다.');
  } else {
    console.error('[probe] PC에 Chrome이 설치되어 있는지 확인하세요.');
  }
  process.exit(1);
}

const page = context.pages()[0] || await context.newPage();
const captured = [];
let capturedChannelUid = null;
let capturedProduct = null;

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

  const productMatch = url.match(/\/i\/v2\/channels\/([^/]+)\/products\/(\d+)/);
  if (!productMatch || productMatch[2] !== target.productNo) return;

  capturedChannelUid ||= decodeURIComponent(productMatch[1]);
  if (response.status() !== 200 || capturedProduct) return;

  try {
    capturedProduct = await response.json();
    console.log('[network-product] captured product JSON from the page request');
  } catch {}
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

  await page.waitForTimeout(3000);

  const result = await page.evaluate(async ({ store, productNo }) => {
    function findChannelUid(root) {
      if (!root || typeof root !== 'object') return null;
      const stack = [root];
      const seen = new Set();
      let count = 0;
      while (stack.length && count < 20000) {
        const value = stack.pop();
        if (!value || typeof value !== 'object' || seen.has(value)) continue;
        seen.add(value);
        count += 1;
        if (typeof value.channelUid === 'string' && value.channelUid.length >= 8) return value.channelUid;
        for (const child of Object.values(value)) {
          if (child && typeof child === 'object') stack.push(child);
        }
      }
      return null;
    }

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

    const roots = [
      window.__PRELOADED_STATE__,
      window.__INITIAL_STATE__,
      window.__NEXT_DATA__
    ].filter(Boolean);
    const stateChannelUid = roots.map(findChannelUid).find(Boolean) || null;

    const resolverUrl = `/i/v1/smart-stores?url=${encodeURIComponent(store)}`;
    const resolver = await getJson(resolverUrl);
    const channel = resolver.json?.channel ?? resolver.json;
    const resolverChannelUid = channel?.channelUid || channel?.channelId || null;
    const channelUid = resolverChannelUid || stateChannelUid;

    let product = null;
    if (channelUid) {
      const productUrl = `/i/v2/channels/${encodeURIComponent(channelUid)}/products/${productNo}?withWindow=false`;
      product = await getJson(productUrl);
    }

    return {
      resolver: {
        status: resolver.status,
        contentType: resolver.contentType,
        channelUid: resolverChannelUid,
        channelName: channel?.channelName ?? null,
        preview: resolver.preview
      },
      stateChannelUid,
      product
    };
  }, target);

  const effectiveChannelUid = result.resolver.channelUid || result.stateChannelUid || capturedChannelUid;
  let productResult = result.product;

  if ((!productResult || productResult.status !== 200 || !productResult.json) && capturedProduct) {
    productResult = {
      status: 200,
      contentType: 'application/json',
      json: capturedProduct,
      preview: null,
      source: 'captured-network'
    };
  }

  if ((!productResult || productResult.status !== 200 || !productResult.json) && effectiveChannelUid) {
    const retried = await fetchProductInPage(page, effectiveChannelUid, target.productNo);
    if (retried.status === 200 && retried.json) productResult = retried;
    else if (!productResult) productResult = retried;
  }

  console.log('[resolver]', JSON.stringify({
    ...result.resolver,
    stateChannelUid: result.stateChannelUid,
    capturedChannelUid,
    effectiveChannelUid
  }, null, 2));

  if (!effectiveChannelUid) {
    console.log('[result] CHANNEL_UID_NOT_FOUND');
    process.exitCode = 2;
  } else if (!productResult) {
    console.log('[result] PRODUCT_API_NOT_CALLED');
    process.exitCode = 3;
  } else if (productResult.status !== 200 || !productResult.json) {
    console.log('[product]', JSON.stringify({
      status: productResult.status,
      contentType: productResult.contentType,
      preview: productResult.preview
    }, null, 2));
    console.log('[result] PRODUCT_API_FAILED');
    process.exitCode = 4;
  } else {
    console.log('[product]', JSON.stringify(summarizeProduct(productResult.json), null, 2));
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
