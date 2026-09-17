package com.techhun.keyboardalert.restock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://m.smartstore.naver.com/swagkey/products/12348949592";

    private WebView webView;
    private EditText urlInput;
    private TextView statusText;
    private TextView resultText;
    private Button inspectButton;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(DEFAULT_URL);
        root.addView(urlInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = new Button(this);
        openButton.setText("상품 열기");
        openButton.setOnClickListener(v -> openProduct());
        buttons.addView(openButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        inspectButton = new Button(this);
        inspectButton.setText("옵션 재고 읽기");
        inspectButton.setEnabled(false);
        inspectButton.setOnClickListener(v -> inspectInventory());
        buttons.addView(inspectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);

        statusText = new TextView(this);
        statusText.setText("대기 중");
        statusText.setTextColor(Color.DKGRAY);
        statusText.setPadding(0, dp(4), 0, dp(4));
        root.addView(statusText);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        String browserUserAgent = settings.getUserAgentString()
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "");
        settings.setUserAgentString(browserUserAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new InventoryBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                inspectButton.setEnabled(false);
                statusText.setText("로딩 중 · " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inspectButton.setEnabled(true);
                statusText.setText("로드 완료 · " + view.getTitle() + "\n" + url);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        ScrollView resultScroll = new ScrollView(this);
        resultText = new TextView(this);
        resultText.setTextIsSelectable(true);
        resultText.setTextSize(13f);
        resultText.setPadding(dp(4), dp(8), dp(4), dp(8));
        resultText.setText("상품을 연 뒤 '옵션 재고 읽기'를 누르세요.");
        resultScroll.addView(resultText);
        root.addView(resultScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(230)
        ));

        setContentView(root);
        openProduct();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openProduct() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("https://")) {
            resultText.setText("https:// SmartStore URL을 입력하세요.");
            return;
        }
        inspectButton.setEnabled(false);
        resultText.setText("페이지 로딩 중...");
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        inspectButton.setEnabled(false);
        statusText.setText("상품 API 확인 중...");
        resultText.setText("페이지 내부에서 상품 API를 조회하고 있습니다...");

        String script = """
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
                      id: option.id ?? null,
                      optionName1: option.optionName1 ?? null,
                      optionName2: option.optionName2 ?? null,
                      optionName3: option.optionName3 ?? null,
                      stockQuantity: Number.isFinite(stock) ? stock : null,
                      available: option.usable !== false && Number.isFinite(stock) && stock > 0
                    };
                  });

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

        webView.evaluateJavascript(script, ignored -> {});
    }

    private class InventoryBridge {
        @JavascriptInterface
        public void onResult(String json) {
            runOnUiThread(() -> handleInventoryResult(json));
        }
    }

    private void handleInventoryResult(String json) {
        inspectButton.setEnabled(true);
        try {
            JSONObject result = new JSONObject(json);
            if (result.optBoolean("ok", false)) {
                statusText.setText("옵션 재고 조회 성공");
                resultText.setText(formatInventory(result));
            } else {
                statusText.setText("옵션 재고 조회 실패 · " + result.optString("error", "UNKNOWN"));
                resultText.setText(result.toString(2));
            }
        } catch (Exception error) {
            statusText.setText("결과 파싱 실패");
            resultText.setText("raw:\n" + json + "\n\nerror:\n" + error);
        }
    }

    private String formatInventory(JSONObject result) {
        StringBuilder output = new StringBuilder();
        output.append(result.optString("title", "상품")).append('\n');
        output.append("상품 재고: ").append(result.opt("stockQuantity")).append('\n');
        output.append("옵션 조합: ").append(result.optInt("optionCombinationCount", 0)).append("개\n\n");

        JSONArray options = result.optJSONArray("options");
        if (options == null || options.length() == 0) {
            output.append("옵션 조합 데이터 없음\n\n");
        } else {
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                StringBuilder name = new StringBuilder();
                appendOptionName(name, option.optString("optionName1", ""));
                appendOptionName(name, option.optString("optionName2", ""));
                appendOptionName(name, option.optString("optionName3", ""));
                output.append(option.optBoolean("available", false) ? "[재고] " : "[품절] ")
                    .append(name.length() == 0 ? option.optString("id", "옵션") : name)
                    .append(" · 수량 ")
                    .append(option.isNull("stockQuantity") ? "?" : option.optInt("stockQuantity"))
                    .append('\n');
            }
        }

        output.append("\nAPI: ").append(result.optString("apiUrl", "-")).append('\n');
        output.append("channelUid: ").append(result.optString("channelUid", "-")).append('\n');
        return output.toString();
    }

    private void appendOptionName(StringBuilder target, String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return;
        if (target.length() > 0) target.append(" / ");
        target.append(value);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
