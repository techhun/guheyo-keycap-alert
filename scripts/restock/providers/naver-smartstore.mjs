import { chromium } from 'playwright';
import { canonicalHttpUrl, clean, uniqueStrings } from './common.mjs';

const NAVER_HOSTS = new Set(['naver.me', 'smartstore.naver.com', 'm.smartstore.naver.com']);
const SMARTSTORE_PATH_RE = /^\/([^/]+)\/products\/(\d+)\/?$/;

export function supportsNaverSmartStoreUrl(value) {
  try {
    return NAVER_HOSTS.has(canonicalHttpUrl(value).hostname.toLowerCase());
  } catch {
    return false;
  }
}

function canonicalFromResolved(value) {
  const url = canonicalHttpUrl(value);
  const host = url.hostname.toLowerCase();
  if (!['smartstore.naver.com', 'm.smartstore.naver.com'].includes(host)) return null;
  const match = url.pathname.match(SMARTSTORE_PATH_RE);
  if (!match) return null;
  return {
    store: match[1],
    productNo: match[2],
    canonicalUrl: `https://smartstore.naver.com/${match[1]}/products/${match[2]}`,
    mobileUrl: `https://m.smartstore.naver.com/${match[1]}/products/${match[2]}`
  };
}

function extractBalancedObject(text, marker) {
  const markerIndex = text.indexOf(marker);
  if (markerIndex < 0) return null;
  const start = text.indexOf('{', markerIndex + marker.length);
  if (start < 0) return null;
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let index = start; index < text.length; index += 1) {
    const char = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
      continue;
    }
    if (char === '{') depth += 1;
    else if (char === '}') {
      depth -= 1;
      if (depth === 0) return text.slice(start, index + 1);
    }
  }
  return null;
}

function parseEmbeddedObjects(scriptRecords) {
  const roots = [];
  const assignmentMarkers = [
    'window.__PRELOADED_STATE__', '__PRELOADED_STATE__',
    'window.__INITIAL_STATE__', '__INITIAL_STATE__',
    'window.__NEXT_DATA__', '__NEXT_DATA__'
  ];

  for (const record of scriptRecords) {
    const text = String(record.text || '').trim();
    if (!text) continue;
    if (record.type === 'application/json' || record.id === '__NEXT_DATA__') {
      try { roots.push(JSON.parse(text)); } catch {}
    }
    for (const marker of assignmentMarkers) {
      const chunk = extractBalancedObject(text, marker);
      if (!chunk) continue;
      try { roots.push(JSON.parse(chunk)); } catch {}
    }
  }
  return roots;
}

function walkObjects(root, visitor, maxNodes = 40000) {
  const stack = [root];
  const seen = new Set();
  let count = 0;
  while (stack.length && count < maxNodes) {
    const value = stack.pop();
    if (!value || typeof value !== 'object' || seen.has(value)) continue;
    seen.add(value);
    count += 1;
    visitor(value);
    if (Array.isArray(value)) {
      for (const item of value) stack.push(item);
    } else {
      for (const item of Object.values(value)) stack.push(item);
    }
  }
}

function findProductModel(roots) {
  let best = null;
  let bestScore = -1;
  for (const root of roots) {
    walkObjects(root, (object) => {
      let score = 0;
      if (object.originProduct && typeof object.originProduct === 'object') score += 10;
      if (object.smartstoreChannelProduct && typeof object.smartstoreChannelProduct === 'object') score += 6;
      if (object.detailAttribute?.optionInfo) score += 8;
      if (object.optionInfo?.optionCombinations) score += 6;
      if (Array.isArray(object.optionCombinations)) score += 4;
      if (object.optionCombinationGroupNames) score += 4;
      if (score > bestScore) {
        best = object;
        bestScore = score;
      }
    });
  }
  return bestScore >= 4 ? best : null;
}

function findOptionInfo(productModel) {
  const candidates = [
    productModel?.originProduct?.detailAttribute?.optionInfo,
    productModel?.detailAttribute?.optionInfo,
    productModel?.optionInfo,
    productModel
  ];
  return candidates.find((candidate) => candidate && typeof candidate === 'object'
    && (Array.isArray(candidate.optionCombinations) || candidate.optionCombinationGroupNames || Array.isArray(candidate.optionSimple))) || {};
}

function findSupplementInfo(productModel) {
  const candidates = [
    productModel?.originProduct?.detailAttribute?.supplementProductInfo,
    productModel?.detailAttribute?.supplementProductInfo,
    productModel?.supplementProductInfo
  ];
  return candidates.find((candidate) => Array.isArray(candidate?.supplementProducts)) || null;
}

function rootProduct(productModel) {
  return productModel?.originProduct && typeof productModel.originProduct === 'object'
    ? productModel.originProduct
    : productModel;
}

function buildSnapshot(productModel, resolved, pageTitle) {
  const product = rootProduct(productModel);
  const optionInfo = findOptionInfo(productModel);
  const groupNamesObject = optionInfo.optionCombinationGroupNames || {};
  const groupNames = [1, 2, 3]
    .map((position) => clean(groupNamesObject[`optionGroupName${position}`]))
    .filter(Boolean);
  const combinations = Array.isArray(optionInfo.optionCombinations) ? optionInfo.optionCombinations : [];
  const optionGroups = groupNames.map((name, index) => ({
    key: `option${index + 1}`,
    name,
    kind: 'variant',
    required: true,
    values: uniqueStrings(combinations.map((combination) => combination[`optionName${index + 1}`]))
      .map((value) => ({ value, label: value, available: true }))
  }));

  let variants = combinations.map((combination, index) => {
    const options = {};
    optionGroups.forEach((group, groupIndex) => {
      options[group.key] = clean(combination[`optionName${groupIndex + 1}`]);
    });
    const stock = Number(combination.stockQuantity);
    return {
      id: String(combination.id ?? `combination-${index}`),
      title: optionGroups.map((group) => options[group.key]).filter(Boolean).join(' / '),
      options,
      available: combination.usable !== false && Number.isFinite(stock) && stock > 0
    };
  });

  const simpleOptions = Array.isArray(optionInfo.optionSimple) ? optionInfo.optionSimple : [];
  if (!variants.length && simpleOptions.length) {
    const groups = new Map();
    for (const option of simpleOptions) {
      const name = clean(option.groupName) || '옵션';
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(option);
    }
    let index = 0;
    for (const [name, values] of groups) {
      const key = `simple${index + 1}`;
      optionGroups.push({
        key,
        name,
        kind: 'variant',
        required: true,
        values: uniqueStrings(values.map((item) => item.name)).map((value) => ({ value, label: value, available: true }))
      });
      index += 1;
    }
    const stock = Number(product?.stockQuantity);
    // Simple options do not expose independent buyer-side stock in the seller schema.
    // Treat usable values as purchasable only when the product itself has stock.
    variants = simpleOptions.map((option, simpleIndex) => ({
      id: String(option.id ?? `simple-${simpleIndex}`),
      title: clean(option.name),
      options: { [optionGroups.find((group) => group.name === clean(option.groupName))?.key || 'simple1']: clean(option.name) },
      available: option.usable !== false && Number.isFinite(stock) && stock > 0
    }));
  }

  if (!variants.length) {
    const stock = Number(product?.stockQuantity ?? productModel?.stockQuantity);
    const status = clean(product?.statusType || productModel?.statusType).toUpperCase();
    variants = [{
      id: 'default',
      title: '기본 상품',
      options: {},
      available: (!status || status === 'SALE') && Number.isFinite(stock) ? stock > 0 : status === 'SALE'
    }];
  }

  const supplementInfo = findSupplementInfo(productModel);
  if (supplementInfo) {
    const grouped = new Map();
    for (const item of supplementInfo.supplementProducts) {
      const name = clean(item.groupName) || '추가상품';
      if (!grouped.has(name)) grouped.set(name, []);
      grouped.get(name).push(item);
    }
    let addonIndex = 0;
    for (const [name, items] of grouped) {
      const key = `addon${addonIndex + 1}`;
      optionGroups.push({
        key,
        name,
        kind: 'addon',
        required: false,
        values: items.map((item) => ({
          id: String(item.id ?? ''),
          value: clean(item.name),
          label: clean(item.name),
          available: item.usable !== false && Number(item.stockQuantity) > 0
        })).filter((item) => item.value)
      });
      addonIndex += 1;
    }
  }

  const channelName = clean(productModel?.smartstoreChannelProduct?.channelProductName);
  const title = clean(product?.name || channelName || pageTitle.replace(/^\[[^\]]+\]\s*/, '')) || `SmartStore ${resolved.productNo}`;
  return {
    provider: 'naver-smartstore',
    canonicalUrl: resolved.canonicalUrl,
    productKey: `naver:${resolved.productNo}`,
    title,
    optionGroups,
    variants,
    meta: { store: resolved.store, productNo: resolved.productNo }
  };
}

async function createContext() {
  const profileDir = clean(process.env.RESTOCK_BROWSER_PROFILE_DIR);
  const channel = clean(process.env.RESTOCK_BROWSER_CHANNEL || 'chrome');
  const options = {
    headless: true,
    locale: 'ko-KR',
    viewport: { width: 1440, height: 1200 },
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
  };
  if (profileDir) {
    const context = await chromium.launchPersistentContext(profileDir, { ...options, ...(channel ? { channel } : {}) });
    return { context, browser: null };
  }
  const browser = await chromium.launch({ headless: true, ...(channel ? { channel } : {}) });
  const context = await browser.newContext(options);
  return { context, browser };
}

export async function inspectNaverSmartStore(value) {
  const original = canonicalHttpUrl(value).toString();
  const { context, browser } = await createContext();
  try {
    const page = await context.newPage();
    let resolved = canonicalFromResolved(original);
    if (!resolved) {
      await page.goto(original, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForTimeout(1200);
      resolved = canonicalFromResolved(page.url());
    }
    if (!resolved) throw new Error(`could not resolve SmartStore product URL: ${original}`);

    if (!canonicalFromResolved(page.url())) {
      await page.goto(resolved.mobileUrl, { waitUntil: 'domcontentloaded', timeout: 45000 });
    }
    await page.waitForTimeout(2200);

    const pageTitle = await page.title().catch(() => '');
    const bodyText = clean((await page.locator('body').innerText({ timeout: 5000 }).catch(() => '')).slice(0, 1000));
    const status = await page.locator('body').evaluate(() => performance.getEntriesByType('navigation')[0]?.responseStatus || 0).catch(() => 0);
    if (/에러페이지|시스템오류|현재 서비스 접속이 불가|Too Many Requests/i.test(`${pageTitle} ${bodyText}`)) {
      const error = new Error(`Naver SmartStore blocked or unavailable${status ? ` (HTTP ${status})` : ''}. Use a persistent bot host/browser session instead of GitHub Actions.`);
      error.code = 'NAVER_SMARTSTORE_BLOCKED';
      throw error;
    }

    const scriptRecords = await page.evaluate(() => [...document.scripts].map((script) => ({
      id: script.id || '',
      type: script.type || '',
      text: script.textContent || ''
    })).filter((record) => record.text.length > 1));
    const roots = parseEmbeddedObjects(scriptRecords);
    const productModel = findProductModel(roots);
    if (!productModel) {
      const error = new Error('SmartStore product data was not found in embedded page state. Provider needs a parser update for this page shape.');
      error.code = 'NAVER_SMARTSTORE_PARSE';
      throw error;
    }
    return buildSnapshot(productModel, resolved, pageTitle);
  } finally {
    await context.close().catch(() => {});
    await browser?.close().catch(() => {});
  }
}
