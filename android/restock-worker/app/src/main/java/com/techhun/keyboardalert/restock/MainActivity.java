package com.techhun.keyboardalert.restock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://m.smartstore.naver.com/swagkey/products/12348949592";
    private static final int[] INTERVAL_VALUES = {15, 30, 60};
    private static final String[] INTERVAL_LABELS = {"15초", "30초", "60초"};

    private static final int COLOR_BG = Color.rgb(247, 248, 250);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(25, 31, 40);
    private static final int COLOR_SUB = Color.rgb(139, 149, 161);
    private static final int COLOR_PRIMARY = Color.rgb(49, 130, 246);
    private static final int COLOR_PRIMARY_SOFT = Color.rgb(235, 244, 255);
    private static final int COLOR_LINE = Color.rgb(242, 244, 246);
    private static final int COLOR_FIELD = Color.rgb(247, 248, 250);
    private static final int COLOR_RED = Color.rgb(240, 68, 82);
    private static final int COLOR_RED_SOFT = Color.rgb(255, 240, 242);
    private static final int COLOR_GREEN = Color.rgb(20, 180, 110);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshMonitorUi();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    private ScrollView rootScroll;
    private LinearLayout browserPanel;
    private WebView webView;
    private EditText urlInput;
    private TextView productTitleText;
    private TextView productMetaText;
    private TextView connectionText;
    private TextView selectedText;
    private TextView monitorStatusText;
    private TextView lastCheckText;
    private TextView resultText;
    private Button inspectButton;
    private Button selectButton;
    private Button detailsButton;
    private Button browserToggleButton;
    private Button monitorActionButton;
    private TextView[] intervalChips;

    private JSONArray latestOptions = new JSONArray();
    private String latestTitle = "";
    private boolean browserVisible = false;
    private boolean autoInspectPending = false;
    private int selectedIntervalSeconds = 30;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);
        requestNotificationPermissionIfNeeded();

        var prefs = MonitorPrefs.prefs(this);
        selectedIntervalSeconds = MonitorPrefs.intervalSeconds(this);

        rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);
        rootScroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(36));
        rootScroll.addView(content, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView eyebrow = text("KEYBOARD RESTOCK", 12f, COLOR_PRIMARY, Typeface.BOLD);
        content.addView(eyebrow);

        TextView heading = text("재입고 감시", 30f, COLOR_TEXT, Typeface.BOLD);
        heading.setPadding(0, dp(4), 0, 0);
        content.addView(heading);

        TextView subtitle = text("원하는 SmartStore 옵션만 골라서 재고가 생기는 순간 알려드려요.", 14f, COLOR_SUB, Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(26));
        content.addView(subtitle);

        LinearLayout stateHero = surface(20, 18);
        content.addView(stateHero, sectionParams());

        TextView stateLabel = text("현재 상태", 13f, COLOR_SUB, Typeface.NORMAL);
        stateHero.addView(stateLabel);

        monitorStatusText = text("감시 중지", 23f, COLOR_TEXT, Typeface.BOLD);
        monitorStatusText.setPadding(0, dp(5), 0, 0);
        stateHero.addView(monitorStatusText);

        lastCheckText = text("감시를 시작하면 조회 상태가 표시됩니다.", 13f, COLOR_SUB, Typeface.NORMAL);
        lastCheckText.setPadding(0, dp(7), 0, 0);
        stateHero.addView(lastCheckText);

        sectionHeading(content, "상품");

        LinearLayout productSurface = surface(20, 18);
        content.addView(productSurface, sectionParams());

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14f);
        urlInput.setTextColor(COLOR_TEXT);
        urlInput.setHintTextColor(COLOR_SUB);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(prefs.getString(MonitorPrefs.KEY_URL, DEFAULT_URL));
        urlInput.setHint("SmartStore 상품 URL");
        urlInput.setPadding(dp(14), 0, dp(14), 0);
        urlInput.setBackground(roundRect(COLOR_FIELD, 12, 0, Color.TRANSPARENT));
        productSurface.addView(urlInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ));

        Button loadButton = primaryButton("상품 불러오기");
        loadButton.setOnClickListener(v -> openProduct());
        productSurface.addView(loadButton, topParams(12));

        productTitleText = text(prefs.getString(MonitorPrefs.KEY_TITLE, "상품을 불러오세요"), 18f, COLOR_TEXT, Typeface.BOLD);
        productTitleText.setPadding(0, dp(20), 0, 0);
        productSurface.addView(productTitleText);

        productMetaText = text("옵션 재고를 불러오면 요약이 표시됩니다.", 13f, COLOR_SUB, Typeface.NORMAL);
        productMetaText.setPadding(0, dp(5), 0, 0);
        productSurface.addView(productMetaText);

        connectionText = text("대기 중", 12f, COLOR_SUB, Typeface.NORMAL);
        connectionText.setPadding(0, dp(8), 0, 0);
        productSurface.addView(connectionText);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setPadding(0, dp(16), 0, 0);
        productSurface.addView(utilityRow, matchWrap());

        inspectButton = softButton("새로고침");
        inspectButton.setEnabled(false);
        inspectButton.setOnClickListener(v -> inspectInventory());
        utilityRow.addView(inspectButton, rowParams(1f, 0));

        detailsButton = softButton("옵션 보기");
        detailsButton.setEnabled(false);
        detailsButton.setOnClickListener(v -> showAllOptions());
        utilityRow.addView(detailsButton, rowParams(1f, 8));

        browserToggleButton = softButton("브라우저 열기");
        browserToggleButton.setOnClickListener(v -> setBrowserVisible(!browserVisible));
        utilityRow.addView(browserToggleButton, rowParams(1f, 8));

        sectionHeading(content, "감시 설정");

        LinearLayout monitorSurface = surface(20, 18);
        content.addView(monitorSurface, sectionParams());

        TextView optionLabel = text("감시할 옵션", 13f, COLOR_SUB, Typeface.NORMAL);
        monitorSurface.addView(optionLabel);

        selectedText = text("선택된 옵션이 없습니다.", 16f, COLOR_TEXT, Typeface.BOLD);
        selectedText.setPadding(0, dp(6), 0, dp(14));
        monitorSurface.addView(selectedText);

        selectButton = softPrimaryButton("옵션 선택");
        selectButton.setEnabled(false);
        selectButton.setOnClickListener(v -> chooseOptions());
        monitorSurface.addView(selectButton, matchWrap());

        TextView intervalTitle = text("조회 주기", 13f, COLOR_SUB, Typeface.NORMAL);
        intervalTitle.setPadding(0, dp(22), 0, dp(9));
        monitorSurface.addView(intervalTitle);

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        monitorSurface.addView(intervalRow, matchWrap());

        intervalChips = new TextView[INTERVAL_VALUES.length];
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            final int seconds = INTERVAL_VALUES[i];
            TextView chip = intervalChip(INTERVAL_LABELS[i]);
            chip.setOnClickListener(v -> {
                if (MonitorPrefs.prefs(this).getBoolean(MonitorPrefs.KEY_RUNNING, false)) return;
                selectedIntervalSeconds = seconds;
                updateIntervalChips();
            });
            intervalChips[i] = chip;
            intervalRow.addView(chip, rowParams(1f, i == 0 ? 0 : 8));
        }
        updateIntervalChips();

        monitorActionButton = primaryButton("감시 시작");
        monitorActionButton.setOnClickListener(v -> {
            boolean running = MonitorPrefs.prefs(this).getBoolean(MonitorPrefs.KEY_RUNNING, false);
            if (running) stopMonitoring();
            else startMonitoring();
        });
        monitorSurface.addView(monitorActionButton, topParams(18));

        resultText = text("상품을 불러오면 현재 재고 요약이 표시됩니다.", 13f, COLOR_SUB, Typeface.NORMAL);
        resultText.setPadding(0, dp(14), 0, 0);
        monitorSurface.addView(resultText);

        browserPanel = surface(20, 16);
        browserPanel.setVisibility(View.GONE);
        content.addView(browserPanel, sectionParams());

        LinearLayout browserHeader = new LinearLayout(this);
        browserHeader.setOrientation(LinearLayout.HORIZONTAL);
        browserHeader.setGravity(Gravity.CENTER_VERTICAL);
        browserPanel.addView(browserHeader, matchWrap());

        TextView browserTitle = text("SmartStore 브라우저", 17f, COLOR_TEXT, Typeface.BOLD);
        browserHeader.addView(browserTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView browserClose = text("닫기", 14f, COLOR_PRIMARY, Typeface.BOLD);
        browserClose.setGravity(Gravity.CENTER);
        browserClose.setPadding(dp(16), dp(8), dp(4), dp(8));
        browserClose.setOnClickListener(v -> setBrowserVisible(false));
        browserHeader.addView(browserClose);

        TextView browserHint = text("로그인하거나 상품 페이지를 직접 확인할 때만 사용하세요.", 12f, COLOR_SUB, Typeface.NORMAL);
        browserHint.setPadding(0, dp(4), 0, dp(12));
        browserPanel.addView(browserHint);

        webView = new WebView(this);
        configureWebView(webView);
        webView.addJavascriptInterface(new InventoryBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                inspectButton.setEnabled(false);
                connectionText.setText("상품 페이지 불러오는 중…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                boolean productPage = isProductUrl(url);
                inspectButton.setEnabled(productPage);

                if (isLoginUrl(url)) {
                    connectionText.setText("네이버 로그인이 필요해요.");
                    setBrowserVisible(true);
                    Toast.makeText(MainActivity.this, "브라우저에서 네이버 로그인 후 상품 페이지로 돌아오세요.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (productPage) {
                    connectionText.setText("상품 페이지 연결 완료");
                    if (autoInspectPending) {
                        autoInspectPending = false;
                        uiHandler.postDelayed(() -> {
                            if (isProductUrl(webView.getUrl())) inspectInventory();
                        }, 900L);
                    }
                } else {
                    connectionText.setText("페이지 로드 완료");
                }
            }
        });
        browserPanel.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(460)
        ));

        setContentView(rootScroll);
        refreshSelectedSummary();
        refreshMonitorUi();
        openProduct();
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        String browserUserAgent = settings.getUserAgentString()
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "");
        settings.setUserAgentString(browserUserAgent);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);
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

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(null, style);
        return view;
    }

    private void sectionHeading(LinearLayout parent, String title) {
        TextView heading = text(title, 18f, COLOR_TEXT, Typeface.BOLD);
        heading.setPadding(dp(2), dp(16), 0, dp(10));
        parent.addView(heading);
    }

    private LinearLayout surface(int radiusDp, int paddingDp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        layout.setBackground(roundRect(COLOR_SURFACE, radiusDp, 0, Color.TRANSPARENT));
        return layout;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topParams(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams rowParams(float weight, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), weight);
        params.leftMargin = dp(leftDp);
        return params;
    }

    private Button baseButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(COLOR_PRIMARY, 12, 0, Color.TRANSPARENT));
        return button;
    }

    private Button softPrimaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(COLOR_PRIMARY);
        button.setBackground(roundRect(COLOR_PRIMARY_SOFT, 12, 0, Color.TRANSPARENT));
        return button;
    }

    private Button softButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(roundRect(COLOR_FIELD, 12, 0, Color.TRANSPARENT));
        return button;
    }

    private TextView intervalChip(String label) {
        TextView chip = text(label, 14f, COLOR_SUB, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(roundRect(COLOR_FIELD, 12, 0, Color.TRANSPARENT));
        return chip;
    }

    private void updateIntervalChips() {
        if (intervalChips == null) return;
        boolean running = MonitorPrefs.prefs(this).getBoolean(MonitorPrefs.KEY_RUNNING, false);
        for (int i = 0; i < intervalChips.length; i++) {
            TextView chip = intervalChips[i];
            boolean selected = INTERVAL_VALUES[i] == selectedIntervalSeconds;
            chip.setTextColor(selected ? COLOR_PRIMARY : COLOR_SUB);
            chip.setBackground(roundRect(selected ? COLOR_PRIMARY_SOFT : COLOR_FIELD, 12, 0, Color.TRANSPARENT));
            chip.setAlpha(running ? 0.55f : 1f);
        }
    }

    private void setBrowserVisible(boolean visible) {
        browserVisible = visible;
        browserPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        browserToggleButton.setText(visible ? "브라우저 닫기" : "브라우저 열기");
        if (visible) {
            browserPanel.post(() -> rootScroll.smoothScrollTo(0, browserPanel.getTop()));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
    }

    private void openProduct() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "https:// SmartStore 상품 URL을 입력하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        inspectButton.setEnabled(false);
        selectButton.setEnabled(false);
        detailsButton.setEnabled(false);
        latestOptions = new JSONArray();
        autoInspectPending = true;
        productMetaText.setText("상품 정보를 불러오는 중이에요.");
        resultText.setText("상품 연결 중…");
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        if (!isProductUrl(webView.getUrl())) {
            Toast.makeText(this, "SmartStore 상품 페이지가 먼저 열려야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        inspectButton.setEnabled(false);
        connectionText.setText("옵션 재고 확인 중…");
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
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
            if (!result.optBoolean("ok", false)) {
                latestOptions = new JSONArray();
                selectButton.setEnabled(false);
                detailsButton.setEnabled(false);
                String error = result.optString("error", "UNKNOWN");
                connectionText.setText("옵션 조회 실패 · " + error);
                productMetaText.setText("옵션 재고를 읽지 못했어요.");
                resultText.setText("브라우저를 열어 상품 페이지 상태를 확인해주세요.");
                return;
            }

            latestTitle = result.optString("title", "상품");
            latestOptions = result.optJSONArray("options");
            if (latestOptions == null) latestOptions = new JSONArray();
            selectButton.setEnabled(latestOptions.length() > 0);
            detailsButton.setEnabled(latestOptions.length() > 0);

            int availableOptions = 0;
            int soldOutOptions = 0;
            for (int i = 0; i < latestOptions.length(); i++) {
                JSONObject option = latestOptions.optJSONObject(i);
                if (option == null) continue;
                if (option.optBoolean("available", false)) availableOptions++;
                else soldOutOptions++;
            }

            productTitleText.setText(latestTitle);
            Object totalStock = result.opt("stockQuantity");
            String totalStockText = (totalStock == null || totalStock == JSONObject.NULL) ? "?" : String.valueOf(totalStock);
            productMetaText.setText("옵션 " + latestOptions.length() + "개  ·  재고 있음 " + availableOptions + "  ·  품절 " + soldOutOptions + "  ·  총 " + totalStockText + "개");
            connectionText.setText("최신 재고로 업데이트했어요.");
            resultText.setText(formatInventorySummary(result));
            pruneInvalidSavedSelections();
        } catch (Exception error) {
            connectionText.setText("결과 처리 실패");
            resultText.setText("결과를 처리하지 못했어요: " + error.getClass().getSimpleName());
        }
    }

    private void pruneInvalidSavedSelections() {
        var prefs = MonitorPrefs.prefs(this);
        String configuredUrl = prefs.getString(MonitorPrefs.KEY_URL, "");
        String currentUrl = urlInput.getText().toString().trim();
        if (!configuredUrl.equals(currentUrl)) return;
        if (prefs.getBoolean(MonitorPrefs.KEY_RUNNING, false)) return;

        Set<String> validIds = new HashSet<>();
        Map<String, String> validLabels = new LinkedHashMap<>();
        for (int i = 0; i < latestOptions.length(); i++) {
            JSONObject option = latestOptions.optJSONObject(i);
            if (option == null) continue;
            String id = option.optString("id", "");
            if (id.isBlank()) continue;
            validIds.add(id);
            validLabels.put(id, optionLabel(option));
        }

        List<String> savedIds = MonitorPrefs.selectedIds(this);
        if (savedIds.isEmpty()) return;
        List<String> retained = new ArrayList<>();
        Map<String, String> retainedLabels = new LinkedHashMap<>();
        for (String id : savedIds) {
            if (!validIds.contains(id)) continue;
            retained.add(id);
            retainedLabels.put(id, validLabels.getOrDefault(id, id));
        }
        if (retained.size() == savedIds.size()) return;

        MonitorPrefs.saveConfig(this, currentUrl, latestTitle, retained, retainedLabels, selectedIntervalSeconds());
        refreshSelectedSummary();
    }

    private void chooseOptions() {
        if (latestOptions.length() == 0) {
            Toast.makeText(this, "먼저 상품 옵션을 불러오세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> saved = new HashSet<>(MonitorPrefs.selectedIds(this));
        String[] labels = new String[latestOptions.length()];
        boolean[] checked = new boolean[latestOptions.length()];
        for (int i = 0; i < latestOptions.length(); i++) {
            JSONObject option = latestOptions.optJSONObject(i);
            String id = option == null ? "" : option.optString("id", "");
            String label = option == null ? "옵션" : optionLabel(option);
            int stock = option == null || option.isNull("stockQuantity") ? -1 : option.optInt("stockQuantity", -1);
            String stockText = stock < 0 ? "재고 ?" : (stock > 0 ? "재고 " + stock + "개" : "품절");
            labels[i] = label + "  ·  " + stockText;
            checked[i] = saved.contains(id);
        }

        new AlertDialog.Builder(this)
            .setTitle("감시할 옵션")
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", (dialog, which) -> {
                List<String> ids = new ArrayList<>();
                Map<String, String> selectedLabels = new LinkedHashMap<>();
                for (int i = 0; i < checked.length; i++) {
                    if (!checked[i]) continue;
                    JSONObject option = latestOptions.optJSONObject(i);
                    if (option == null) continue;
                    String id = option.optString("id", "");
                    if (id.isBlank()) continue;
                    ids.add(id);
                    selectedLabels.put(id, optionLabel(option));
                }
                if (ids.isEmpty()) {
                    Toast.makeText(this, "최소 한 개 옵션을 선택하세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                MonitorPrefs.saveConfig(
                    this,
                    urlInput.getText().toString().trim(),
                    latestTitle,
                    ids,
                    selectedLabels,
                    selectedIntervalSeconds()
                );
                refreshSelectedSummary();
                Toast.makeText(this, ids.size() + "개 옵션을 저장했어요.", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void showAllOptions() {
        if (latestOptions.length() == 0) return;
        String[] rows = new String[latestOptions.length()];
        for (int i = 0; i < latestOptions.length(); i++) {
            JSONObject option = latestOptions.optJSONObject(i);
            if (option == null) {
                rows[i] = "옵션";
                continue;
            }
            int stock = option.isNull("stockQuantity") ? -1 : option.optInt("stockQuantity", -1);
            rows[i] = (stock > 0 ? "●  " : "○  ") + optionLabel(option) + "   " + (stock < 0 ? "?" : stock + "개");
        }
        new AlertDialog.Builder(this)
            .setTitle("전체 옵션")
            .setItems(rows, null)
            .setPositiveButton("닫기", null)
            .show();
    }

    private int selectedIntervalSeconds() {
        return selectedIntervalSeconds;
    }

    private void startMonitoring() {
        List<String> ids = MonitorPrefs.selectedIds(this);
        if (ids.isEmpty()) {
            Toast.makeText(this, "감시할 옵션을 먼저 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        String configuredUrl = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_URL, "");
        String currentUrl = urlInput.getText().toString().trim();
        if (!configuredUrl.equals(currentUrl)) {
            Toast.makeText(this, "상품 URL이 바뀌었어요. 상품을 다시 불러오고 옵션을 선택해주세요.", Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, String> labels = MonitorPrefs.selectedLabels(this);
        String title = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_TITLE, latestTitle);
        MonitorPrefs.saveConfig(this, currentUrl, title, ids, labels, selectedIntervalSeconds());
        Intent service = new Intent(this, MonitorService.class);
        startForegroundService(service);
        MonitorPrefs.setRunning(this, true);
        refreshMonitorUi();
        Toast.makeText(this, selectedIntervalSeconds() + "초마다 확인할게요.", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        startService(intent);
        MonitorPrefs.setRunning(this, false);
        refreshMonitorUi();
        Toast.makeText(this, "감시를 중지했어요.", Toast.LENGTH_SHORT).show();
    }

    private void refreshSelectedSummary() {
        Map<String, String> labels = MonitorPrefs.selectedLabels(this);
        if (labels.isEmpty()) {
            selectedText.setText("아직 선택하지 않았어요");
            return;
        }
        if (labels.size() == 1) {
            selectedText.setText(labels.values().iterator().next());
            return;
        }
        String first = labels.values().iterator().next();
        selectedText.setText(first + " 외 " + (labels.size() - 1) + "개");
    }

    private void refreshMonitorUi() {
        var prefs = MonitorPrefs.prefs(this);
        boolean running = prefs.getBoolean(MonitorPrefs.KEY_RUNNING, false);
        String status = prefs.getString(MonitorPrefs.KEY_LAST_STATUS, running ? "감시 준비 중" : "대기 중");
        long lastCheck = prefs.getLong(MonitorPrefs.KEY_LAST_CHECK, 0L);

        monitorStatusText.setText(running ? "감시 중" : "감시 중지");
        monitorStatusText.setTextColor(running ? COLOR_GREEN : COLOR_TEXT);
        monitorActionButton.setText(running ? "감시 중지" : "감시 시작");
        monitorActionButton.setTextColor(running ? COLOR_RED : Color.WHITE);
        monitorActionButton.setBackground(roundRect(running ? COLOR_RED_SOFT : COLOR_PRIMARY, 12, 0, Color.TRANSPARENT));

        if (lastCheck > 0L) {
            String time = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(new Date(lastCheck));
            lastCheckText.setText(status + "  ·  " + time + (running ? "  ·  " + MonitorPrefs.intervalSeconds(this) + "초 간격" : ""));
        } else {
            lastCheckText.setText(running ? "첫 재고 확인을 기다리고 있어요." : "감시를 시작하면 조회 상태가 표시돼요.");
        }

        selectedIntervalSeconds = running ? MonitorPrefs.intervalSeconds(this) : selectedIntervalSeconds;
        updateIntervalChips();
    }

    private String formatInventorySummary(JSONObject result) {
        JSONArray options = result.optJSONArray("options");
        if (options == null || options.length() == 0) return "옵션 데이터가 없어요.";

        int available = 0;
        int soldOut = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            if (option.optBoolean("available", false)) available++;
            else soldOut++;
        }
        return "재고 있음 " + available + "개  ·  품절 " + soldOut + "개\n원하는 옵션을 고른 뒤 감시를 시작하세요.";
    }

    static String optionLabel(JSONObject option) {
        StringBuilder target = new StringBuilder();
        for (String key : new String[]{"optionName1", "optionName2", "optionName3"}) {
            String value = option.optString(key, "");
            if (value.isBlank() || "null".equals(value)) continue;
            if (target.length() > 0) target.append(" / ");
            target.append(value);
        }
        return target.length() > 0 ? target.toString() : option.optString("id", "옵션");
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(statusRefresh);
        uiHandler.post(statusRefresh);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (browserVisible && webView != null && webView.canGoBack()) webView.goBack();
        else if (browserVisible) setBrowserVisible(false);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
