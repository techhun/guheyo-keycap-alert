package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LoginActivity extends Activity {
    static final String EXTRA_TARGET_URL = "target_url";
    private static final String SMARTSTORE_HOME = "https://m.smartstore.naver.com/";

    private WebView webView;
    private String targetUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        targetUrl = getIntent().getStringExtra(EXTRA_TARGET_URL);
        if (targetUrl == null || targetUrl.isBlank()) targetUrl = SMARTSTORE_HOME;

        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        root.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ));

        TextView close = new TextView(this);
        close.setText("닫기");
        close.setTextSize(15f);
        close.setTextColor(Color.rgb(49, 130, 246));
        close.setTypeface(null, Typeface.BOLD);
        close.setGravity(Gravity.CENTER_VERTICAL);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        MainActivity.configureWebView(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isLoginUrl(url)) return;
                if (loginCompleted(url)) {
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
        root.requestApplyInsets();

        String loginUrl = "https://nid.naver.com/nidlogin.login?url=" + Uri.encode(targetUrl);
        webView.loadUrl(loginUrl);
    }

    private boolean loginCompleted(String url) {
        if (url == null || !url.contains("smartstore.naver.com")) return false;
        if (targetUrl.contains("/products/")) return isProductUrl(url);
        return true;
    }

    private boolean isProductUrl(String url) {
        return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private boolean isLoginUrl(String url) {
        return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login"));
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
