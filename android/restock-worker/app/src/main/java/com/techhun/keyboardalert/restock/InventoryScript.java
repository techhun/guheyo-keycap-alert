package com.techhun.keyboardalert.restock;

final class InventoryScript {
    private InventoryScript() {}

    static final String SCRIPT = """
        (() => {
          const send = (value) => window.RestockBridge.onResult(JSON.stringify(value));
          (async () => {
            try {
              const productMatch = location.pathname.match(/\\/products\\/(\\d+)/);
              const productNo = productMatch ? productMatch[1] : null;
              if (!productNo) {
                send({ ok: false, error: 'PRODUCT_NO_NOT_FOUND', pageUrl: location.href, title: document.title });
                return;
              }

              function findChannelUid(root) {
                if (!root || typeof root !== 'object') return null;
                const stack = [root];
                const seen = new Set();
                let count = 0;
                while (stack.length && count < 30000) {
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

              let channelUid = null;
              let observedApiUrl = null;
              const roots = [window.__PRELOADED_STATE__, window.__INITIAL_STATE__, window.__NEXT_DATA__].filter(Boolean);
              for (const root of roots) {
                channelUid = findChannelUid(root);
                if (channelUid) break;
              }

              const resources = performance.getEntriesByType('resource').map((entry) => entry.name || '');
              for (const resourceUrl of resources) {
                const match = resourceUrl.match(/\\/i\\/v2\\/channels\\/([^/]+)\\/products\\/(\\d+)/);
                if (!match) continue;
                if (!channelUid) channelUid = decodeURIComponent(match[1]);
                if (match[2] === productNo) observedApiUrl = resourceUrl;
              }

              if (!channelUid) {
                send({ ok: false, error: 'CHANNEL_UID_NOT_FOUND', pageUrl: location.href, title: document.title });
                return;
              }

              const candidates = [];
              if (observedApiUrl) candidates.push(observedApiUrl);
              candidates.push(`/i/v2/channels/${encodeURIComponent(channelUid)}/products/${productNo}?withWindow=false`);
              candidates.push(`https://smartstore.naver.com/i/v2/channels/${encodeURIComponent(channelUid)}/products/${productNo}?withWindow=false`);

              let response = null;
              let text = '';
              let usedApiUrl = null;
              const attempts = [];
              for (const apiUrl of [...new Set(candidates)]) {
                try {
                  const current = await fetch(apiUrl, {
                    credentials: 'include',
                    headers: { accept: 'application/json, text/plain, */*' }
                  });
                  const currentText = await current.text();
                  attempts.push({ url: apiUrl, status: current.status });
                  if (current.ok) {
                    response = current;
                    text = currentText;
                    usedApiUrl = apiUrl;
                    break;
                  }
                  if (!response) {
                    response = current;
                    text = currentText;
                    usedApiUrl = apiUrl;
                  }
                } catch (error) {
                  attempts.push({ url: apiUrl, error: String(error) });
                }
              }

              if (!response || !response.ok) {
                send({
                  ok: false,
                  error: 'PRODUCT_API_FAILED',
                  status: response ? response.status : null,
                  pageUrl: location.href,
                  apiUrl: usedApiUrl,
                  attempts,
                  preview: text.replace(/\\s+/g, ' ').slice(0, 300)
                });
                return;
              }

              let data;
              try {
                data = JSON.parse(text);
              } catch (error) {
                send({ ok: false, error: 'INVALID_PRODUCT_JSON', status: response.status, apiUrl: usedApiUrl, preview: text.slice(0, 300) });
                return;
              }

              const product = data.originProduct && typeof data.originProduct === 'object'
                ? data.originProduct
                : data;
              const optionInfo = product?.detailAttribute?.optionInfo
                || data?.detailAttribute?.optionInfo
                || data?.optionInfo
                || data;
              const combinations = Array.isArray(optionInfo?.optionCombinations)
                ? optionInfo.optionCombinations
                : [];

              const options = combinations.map((option) => {
                const stock = Number(option.stockQuantity);
                return {
                  id: String(option.id ?? ''),
                  optionName1: option.optionName1 ?? null,
                  optionName2: option.optionName2 ?? null,
                  optionName3: option.optionName3 ?? null,
                  stockQuantity: Number.isFinite(stock) ? stock : null,
                  available: option.usable !== false && Number.isFinite(stock) && stock > 0
                };
              });

              if (!options.length) {
                const stock = Number(product?.stockQuantity ?? data?.stockQuantity);
                options.push({
                  id: 'default',
                  optionName1: '기본 상품',
                  optionName2: null,
                  optionName3: null,
                  stockQuantity: Number.isFinite(stock) ? stock : null,
                  available: Number.isFinite(stock) && stock > 0
                });
              }

              send({
                ok: true,
                pageUrl: location.href,
                apiUrl: usedApiUrl,
                title: product?.name || data?.smartstoreChannelProduct?.channelProductName || document.title,
                channelUid,
                id: product?.id ?? data?.id ?? null,
                productNo: data?.productNo ?? productNo,
                statusType: product?.statusType || data?.statusType || data?.productStatusType || null,
                stockQuantity: product?.stockQuantity ?? data?.stockQuantity ?? null,
                optionCombinationCount: options.length,
                options,
                attempts
              });
            } catch (error) {
              send({ ok: false, error: 'JS_ERROR', message: String(error && (error.stack || error.message) || error) });
            }
          })();
          return 'STARTED';
        })()
        """;
}
