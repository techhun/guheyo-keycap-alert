package com.techhun.keyboardalert.restock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

    private static final int COLOR_BG = Color.rgb(246, 247, 249);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_BORDER = Color.rgb(226, 229, 234);
    private static final int COLOR_TEXT = Color.rgb(30, 34, 40);
    private static final int COLOR_SUB = Color.rgb(101, 108, 118);
    private static final int COLOR_PRIMARY = Color.rgb(31, 126, 83);
    private static final int COLOR_DANGER = Color.rgb(180, 62, 62);
    private static final int COLOR_NEUTRAL = Color.rgb(92, 99, 108);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshMonitorUi();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    private WebView webView;
    private EditText urlInput;
    private TextView statusText;
    private TextView productTitleText;
    private TextView productMetaText;
    private TextView resultText;
    private TextView selectedText;
    private TextView monitorStatusText;
    private TextView lastCheckText;
    private Button inspectButton;
    private Button selectButton;
    private Button detailsButton;
    private Button browserToggleButton;
    private Button startButton;
    private Button stopButton;
    private Spinner intervalSpinner;

    private JSONArray latestOptions = new JSONArray();
    private String latestTitle = "";
    private boolean browserVisible = false;
    private boolean autoInspectPending = false;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);
        requestNotificationPermissionIfNeeded();

        var prefs = MonitorPrefs.prefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView heading = new TextView(this);
        heading.setText("Keyboard Restock");
        heading.setTextSize(25f);
        heading.setTextColor(COLOR_TEXT);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText("SmartStore 옵션 재입고를 기기에서 직접 감시합니다.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(COLOR_SUB);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        content.addView(subtitle);

        LinearLayout productCard = card();
        content.addView(productCard, cardParams());

        TextView productSection = sectionTitle("상품");
        productCard.addView(productSection);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14f);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(prefs.getString(MonitorPrefs.KEY_URL, DEFAULT_URL));
        urlInput.setHint("SmartStore 상품 URL");
        productCard.addView(urlInput, matchWrap());

        Button loadButton = button("상품 불러오기", COLOR_PRIMARY);
        loadButton.setOnClickListener(v -> openProduct());
        productCard.addView(loadButton, matchWrapTop(8));

        productTitleText = new TextView(this);
        productTitleText.setText(prefs.getString(MonitorPrefs.KEY_TITLE, "상품을 불러오세요"));
        productTitleText.setTextColor(COLOR_TEXT);
        productTitleText.setTextSize(17f);
        productTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
        productTitleText.setPadding(0, dp(14), 0, dp(2));
        productCard.addView(productTitleText);

        productMetaText = new TextView(this);
        productMetaText.setText("옵션 재고를 불러오면 요약이 표시됩니다.");
        productMetaText.setTextColor(COLOR_SUB);
        productMetaText.setTextSize(13f);
        productCard.addView(productMetaText);

        statusText = new TextView(this);
        statusText.setText("대기 중");
        statusText.setTextColor(COLOR_SUB);
        statusText.setTextSize(12f);
        statusText.setPadding(0, dp(6), 0, 0);
        productCard.addView(statusText);

        LinearLayout productActions = new LinearLayout(this);
        productActions.setOrientation(LinearLayout.HORIZONTAL);
        productActions.setPadding(0, dp(10), 0, 0);
        productCard.addView(productActions, matchWrap());

        inspectButton = button("옵션 새로고침", COLOR_NEUTRAL);
        inspectButton.setEnabled(false);
        inspectButton.setOnClickListener(v -> inspectInventory());
        productActions.addView(inspectButton, rowButtonParams(1f, 0));

        detailsButton = button("전체 옵션 보기", COLOR_NEUTRAL);
        detailsButton.setEnabled(false);
        detailsButton.setOnClickListener(v -> showAllOptions());
        productActions.addView(detailsButton, rowButtonParams(1f, 8));

        browserToggleButton = button("브라우저 보기", COLOR_NEUTRAL);
        browserToggleButton.setOnClickListener(v -> setBrowserVisible(!browserVisible));
        productCard.addView(browserToggleButton, matchWrapTop(8));

        LinearLayout monitorCard = card();
        content.addView(monitorCard, cardParams());
        monitorCard.addView(sectionTitle("감시 설정"));

        selectButton = button("감시 옵션 선택", COLOR_PRIMARY);
        selectButton.setEnabled(false);
        selectButton.setOnClickListener(v -> chooseOptions());
        monitorCard.addView(selectButton, matchWrap());

        selectedText = new TextView(this);
        selectedText.setTextColor(COLOR_TEXT);
        selectedText.setTextSize(14f);
        selectedText.setPadding(0, dp(10), 0, dp(8));
        monitorCard.addView(selectedText);

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(Gravity.CENTER_VERTICAL);
        monitorCard.addView(intervalRow, matchWrap());

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("조회 주기");
        intervalLabel.setTextColor(COLOR_SUB);
        intervalLabel.setTextSize(14f);
        intervalRow.addView(intervalLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        intervalSpinner = new Spinner(this);
        ArrayAdapter<String> intervalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, INTERVAL_LABELS);
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpinner.setAdapter(intervalAdapter);
        int savedInterval = MonitorPrefs.intervalSeconds(this);
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            if (INTERVAL_VALUES[i] == savedInterval) intervalSpinner.setSelection(i);
        }
        intervalRow.addView(intervalSpinner, new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout monitorButtons = new LinearLayout(this);
        monitorButtons.setOrientation(LinearLayout.HORIZONTAL);
        monitorButtons.setPadding(0, dp(10), 0, 0);
        monitorCard.addView(monitorButtons, matchWrap());

        startButton = button("감시 시작", COLOR_PRIMARY);
        startButton.setOnClickListener(v -> startMonitoring());
        monitorButtons.addView(startButton, rowButtonParams(1f, 0));

        stopButton = button("감시 중지", COLOR_DANGER);
        stopButton.setOnClickListener(v -> stopMonitoring());
        monitorButtons.addView(stopButton, rowButtonParams(1f, 8));

        LinearLayout stateCard = card();
        content.addView(stateCard, cardParams());
        stateCard.addView(sectionTitle("현재 상태"));

        monitorStatusText = new TextView(this);
        monitorStatusText.setTextSize(16f);
        monitorStatusText.setTypeface(null, android.graphics.Typeface.BOLD);
        stateCard.addView(monitorStatusText);

        lastCheckText = new TextView(this);
        lastCheckText.setTextColor(COLOR_SUB);
        lastCheckText.setTextSize(13f);
        lastCheckText.setPadding(0, dp(5), 0, 0);
        stateCard.addView(lastCheckText);

        resultText = new TextView(this);
        resultText.setTextColor(COLOR_SUB);
        resultText.setTextSize(13f);
        resultText.setPadding(0, dp(8), 0, 0);
        resultText.setText("상품을 불러오면 현재 옵션 재고가 여기에 요약됩니다.");
        stateCard.addView(resultText);

        LinearLayout browserCard = card();
        content.addView(browserCard, cardParams());
        TextView browserTitle = sectionTitle("SmartStore 브라우저");
        browserCard.addView(browserTitle);

        TextView browserHint = new TextView(this);
        browserHint.setText("로그인이 필요하거나 상품 페이지를 직접 확인할 때만 펼쳐서 사용하세요.");
        browserHint.setTextColor(COLOR_SUB);
        browserHint.setTextSize(12f);
        browserHint.setPadding(0, 0, 0, dp(6));
        browserCard.addView(browserHint);

        webView = new WebView(this);
        configureWebView(webView);
        webView.addJavascriptInterface(new InventoryBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                inspectButton.setEnabled(false);
                statusText.setText("상품 페이지 로딩 중…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                boolean productPage = isProductUrl(url);
                inspectButton.setEnabled(productPage);
                if (isLoginUrl(url)) {
                    statusText.setText("네이버 로그인이 필요합니다.");
                    setBrowserVisible(true);
                    Toast.makeText(MainActivity.this, "브라우저에서 네이버 로그인 후 상품 페이지로 돌아오세요.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (productPage) {
                    statusText.setText("상품 페이지 연결 완료");
                    if (autoInspectPending) {
                        autoInspectPending = false;
                        uiHandler.postDelayed(() -> {
                            if (isProductUrl(webView.getUrl())) inspectInventory();
                        }, 900L);
                    }
                } else {
                    statusText.setText("페이지 로드 완료");
                }
            }
        });
        browserCard.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ));

        setContentView(scroll);
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

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_CARD);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), COLOR_BORDER);
        layout.setBackground(background);
        return layout;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams rowButtonParams(float weight, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.leftMargin = dp(leftDp);
        return params;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_SUB);
        view.setTextSize(12f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private Button button(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        button.setMinHeight(dp(46));
        return button;
    }

    private void setBrowserVisible(boolean visible) {
        browserVisible = visible;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) webView.getLayoutParams();
        params.height = dp(visible ? 360 : 1);
        webView.setLayoutParams(params);
        browserToggleButton.setText(visible ? "브라우저 숨기기" : "브라우저 보기");
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
        productMetaText.setText("상품 페이지를 불러오는 중입니다…");
        resultText.setText("상품 연결 중…");
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        if (!isProductUrl(webView.getUrl())) {
            Toast.makeText(this, "SmartStore 상품 페이지가 먼저 열려야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        inspectButton.setEnabled(false);
        statusText.setText("옵션 재고 확인 중…");
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
                statusText.setText("옵션 조회 실패 · " + error);
                productMetaText.setText("옵션 재고를 읽지 못했습니다.");
                resultText.setText("조회 실패: " + error + "\n브라우저 보기에서 상품 페이지 상태를 확인해보세요.");
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
            productMetaText.setText("옵션 " + latestOptions.length() + "개 · 재고 있음 " + availableOptions + " · 품절 " + soldOutOptions + " · 총 재고 " + totalStockText);
            statusText.setText("옵션 재고 업데이트 완료");
            resultText.setText(formatInventorySummary(result));
            pruneInvalidSavedSelections();
        } catch (Exception error) {
            statusText.setText("결과 처리 실패");
            resultText.setText("결과를 처리하지 못했습니다: " + error.getClass().getSimpleName());
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
            .setTitle("감시할 옵션 선택")
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
                Toast.makeText(this, ids.size() + "개 옵션을 감시 대상으로 저장했습니다.", Toast.LENGTH_SHORT).show();
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
            rows[i] = (stock > 0 ? "● " : "○ ") + optionLabel(option) + "  ·  " + (stock < 0 ? "?" : stock + "개");
        }
        new AlertDialog.Builder(this)
            .setTitle("전체 옵션 재고")
            .setItems(rows, null)
            .setPositiveButton("닫기", null)
            .show();
    }

    private int selectedIntervalSeconds() {
        int position = intervalSpinner.getSelectedItemPosition();
        if (position < 0 || position >= INTERVAL_VALUES.length) return 30;
        return INTERVAL_VALUES[position];
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
            Toast.makeText(this, "상품 URL이 바뀌었습니다. 상품을 다시 불러오고 옵션을 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, String> labels = MonitorPrefs.selectedLabels(this);
        String title = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_TITLE, latestTitle);
        MonitorPrefs.saveConfig(this, currentUrl, title, ids, labels, selectedIntervalSeconds());
        Intent service = new Intent(this, MonitorService.class);
        startForegroundService(service);
        MonitorPrefs.setRunning(this, true);
        refreshMonitorUi();
        Toast.makeText(this, selectedIntervalSeconds() + "초 간격 재입고 감시를 시작했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        startService(intent);
        MonitorPrefs.setRunning(this, false);
        refreshMonitorUi();
        Toast.makeText(this, "재입고 감시를 중지했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void refreshSelectedSummary() {
        Map<String, String> labels = MonitorPrefs.selectedLabels(this);
        if (labels.isEmpty()) {
            selectedText.setText("선택된 옵션이 없습니다.");
            return;
        }
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (String label : labels.values()) {
            if (count++ >= 4) {
                summary.append("외 ").append(labels.size() - 4).append("개");
                break;
            }
            summary.append("• ").append(label).append('\n');
        }
        selectedText.setText(summary.toString().trim());
    }

    private void refreshMonitorUi() {
        var prefs = MonitorPrefs.prefs(this);
        boolean running = prefs.getBoolean(MonitorPrefs.KEY_RUNNING, false);
        String status = prefs.getString(MonitorPrefs.KEY_LAST_STATUS, running ? "감시 준비 중" : "대기 중");
        long lastCheck = prefs.getLong(MonitorPrefs.KEY_LAST_CHECK, 0L);

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        intervalSpinner.setEnabled(!running);
        monitorStatusText.setText((running ? "● 감시 중" : "○ 감시 중지") + "  ·  " + status);
        monitorStatusText.setTextColor(running ? COLOR_PRIMARY : COLOR_NEUTRAL);

        if (lastCheck > 0L) {
            String time = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA).format(new Date(lastCheck));
            lastCheckText.setText("마지막 상태 갱신  " + time + (running ? "  ·  " + MonitorPrefs.intervalSeconds(this) + "초 간격" : ""));
        } else {
            lastCheckText.setText(running ? "첫 재고 확인을 기다리는 중입니다." : "감시를 시작하면 최근 조회 상태가 표시됩니다.");
        }
    }

    private String formatInventorySummary(JSONObject result) {
        JSONArray options = result.optJSONArray("options");
        if (options == null || options.length() == 0) return "옵션 데이터가 없습니다.";

        int available = 0;
        int soldOut = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            if (option.optBoolean("available", false)) available++;
            else soldOut++;
        }
        return "현재 재고 있음 " + available + "개 옵션 · 품절 " + soldOut + "개 옵션\n감시할 옵션을 선택한 뒤 '감시 시작'을 누르세요.";
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
