package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LoginActivity extends Activity {
    static final String EXTRA_TARGET_URL = "target_url";

    private WebView webView;
    private String targetUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetUrl = getIntent().getStringExtra(EXTRA_TARGET_URL);
        if (targetUrl == null || targetUrl.isBlank()) {
            targetUrl = "https://m.smartstore.naver.com/";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(10));
        root.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView close = new TextView(this);
        close.setText("닫기");
        close.setTextSize(15f);
        close.setTextColor(Color.rgb(49, 130, 246));
        close.setTypeface(null, Typeface.BOLD);
        close.setPadding(0, dp(10), dp(20), dp(10));
        close.setOnClickListener(v -> finish());
        header.addView(close);

        TextView title = new TextView(this);
        title.setText("네이버 로그인");
        title.setTextSize(20f);
        title.setTextColor(Color.rgb(25, 31, 40));
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        webView = new WebView(this);
        MainActivity.configureWebView(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isProductUrl(url)) {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_TARGET_URL, targetUrl);
                    setResult(RESULT_OK, result);
                    finish();
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        setContentView(root);
        webView.loadUrl(targetUrl);
    }

    private boolean isProductUrl(String url) {
        return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
