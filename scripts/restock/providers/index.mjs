import { inspectShopify, supportsShopifyUrl } from './shopify.mjs';
import { inspectNaverSmartStore, supportsNaverSmartStoreUrl } from './naver-smartstore.mjs';
import { canonicalHttpUrl } from './common.mjs';

const PROVIDERS = [
  { id: 'shopify', supports: supportsShopifyUrl, inspect: inspectShopify },
  { id: 'naver-smartstore', supports: supportsNaverSmartStoreUrl, inspect: inspectNaverSmartStore }
];

export function detectProvider(value) {
  const url = canonicalHttpUrl(value).toString();
  const provider = PROVIDERS.find((candidate) => candidate.supports(url));
  if (!provider) throw new Error(`unsupported restock URL: ${new URL(url).hostname}`);
  return provider;
}

export async function inspectProduct(value, expectedProvider = '') {
  const provider = expectedProvider
    ? PROVIDERS.find((candidate) => candidate.id === expectedProvider)
    : detectProvider(value);
  if (!provider) throw new Error(`unknown restock provider: ${expectedProvider}`);
  return provider.inspect(value);
}

export function providerIds() {
  return PROVIDERS.map((provider) => provider.id);
}
