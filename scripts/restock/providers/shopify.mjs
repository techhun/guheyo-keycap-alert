import { canonicalHttpUrl, clean, sleep, uniqueStrings } from './common.mjs';

const KNOWN_SHOPIFY_HOSTS = new Set(['swagkeys.com', 'www.swagkeys.com']);

export function supportsShopifyUrl(value) {
  try {
    const url = canonicalHttpUrl(value);
    return KNOWN_SHOPIFY_HOSTS.has(url.hostname.toLowerCase()) && /^\/products\/[^/]+\/?$/.test(url.pathname);
  } catch {
    return false;
  }
}

export function normalizeShopifyUrl(value) {
  const url = canonicalHttpUrl(value);
  const host = url.hostname.toLowerCase();
  if (!KNOWN_SHOPIFY_HOSTS.has(host)) throw new Error(`unsupported Shopify host: ${url.hostname}`);
  if (!/^\/products\/[^/]+\/?$/.test(url.pathname)) throw new Error(`not a Shopify product URL: ${url}`);
  url.hostname = host === 'www.swagkeys.com' ? 'swagkeys.com' : host;
  url.protocol = 'https:';
  url.search = '';
  url.pathname = url.pathname.replace(/\/+$/, '');
  return url.toString();
}

async function fetchProductJson(productUrl) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(`${productUrl}.js`, {
        headers: {
          Accept: 'application/json,text/javascript,*/*;q=0.8',
          'User-Agent': 'Mozilla/5.0 keyboard-alert/1.0'
        },
        signal: AbortSignal.timeout(15000)
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const product = await response.json();
      if (!Array.isArray(product?.variants) || !Array.isArray(product?.options)) {
        throw new Error('invalid Shopify product payload');
      }
      return product;
    } catch (error) {
      lastError = error;
      if (attempt < 3) await sleep(attempt * 1500);
    }
  }
  throw lastError;
}

export async function inspectShopify(value) {
  const canonicalUrl = normalizeShopifyUrl(value);
  const product = await fetchProductJson(canonicalUrl);
  const optionGroups = (product.options || []).map((option, index) => ({
    key: `option${Number(option.position) || index + 1}`,
    name: clean(option.name) || `옵션 ${index + 1}`,
    kind: 'variant',
    required: true,
    values: uniqueStrings(option.values).map((item) => ({ value: item, label: item, available: true }))
  }));

  const variants = (product.variants || []).map((variant) => {
    const options = {};
    for (let index = 0; index < optionGroups.length; index += 1) {
      options[optionGroups[index].key] = clean(variant[`option${index + 1}`]);
    }
    return {
      id: String(variant.id),
      title: clean(variant.title),
      options,
      available: Boolean(variant.available)
    };
  });

  return {
    provider: 'shopify',
    canonicalUrl,
    productKey: `shopify:${product.id || canonicalUrl}`,
    title: clean(product.title) || canonicalUrl,
    optionGroups,
    variants,
    meta: { vendor: clean(product.vendor) }
  };
}
