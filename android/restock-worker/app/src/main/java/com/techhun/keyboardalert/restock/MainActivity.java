package com.techhun.keyboardalert.restock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://m.smartstore.naver.com/swagkey/products/12348949592";
    private static final int[] INTERVAL_VALUES = {15, 30, 60};
    private static final String[] INTERVAL_LABELS = {"15초", "30초", "60초"};

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
    private TextView resultText;
    private TextView selectedText;
    private TextView monitorStatusText;
    private Button inspectButton;
    private Button selectButton;
    private Button startButton;
    private Button stopButton;
    private Spinner intervalSpinner;
    private JSONArray latestOptions = new JSONArray();
    private String latestTitle = "";

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);
        requestNotificationPermissionIfNeeded();

        var prefs = MonitorPrefs.prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView heading = new TextView(this);
        heading.setText("Keyboard Restock");
        heading.setTextSize(22f);
        heading.setTextColor(Color.BLACK);
        heading.setPadding(0, 0, 0, dp(6));
        root.addView(heading);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(prefs.getString(MonitorPrefs.KEY_URL, DEFAULT_URL));
        root.addView(urlInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout browserButtons = new LinearLayout(this);
        browserButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = new Button(this);
        openButton.setText("상품 열기");
        openButton.setOnClickListener(v -> openProduct());
        browserButtons.addView(openButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        inspectButton = new Button(this);
        inspectButton.setText("옵션 불러오기");
        inspectButton.setEnabled(false);
        inspectButton.setOnClickListener(v -> inspectInventory());
        browserButtons.addView(inspectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(browserButtons);

        statusText = new TextView(this);
        statusText.setText("대기 중");
        statusText.setTextColor(Color.DKGRAY);
        statusText.setPadding(0, dp(3), 0, dp(5));
        root.addView(statusText);

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);

        selectButton = new Button(this);
        selectButton.setText("감시 옵션 선택");
        selectButton.setEnabled(false);
        selectButton.setOnClickListener(v -> chooseOptions());
        controlRow.addView(selectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));

        intervalSpinner = new Spinner(this);
        ArrayAdapter<String> intervalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, INTERVAL_LABELS);
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpinner.setAdapter(intervalAdapter);
        int savedInterval = MonitorPrefs.intervalSeconds(this);
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            if (INTERVAL_VALUES[i] == savedInterval) intervalSpinner.setSelection(i);
        }
        controlRow.addView(intervalSpinner, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(controlRow);

        selectedText = new TextView(this);
        selectedText.setTextColor(Color.DKGRAY);
        selectedText.setPadding(dp(4), dp(4), dp(4), dp(6));
        root.addView(selectedText);

        LinearLayout monitorButtons = new LinearLayout(this);
        monitorButtons.setOrientation(LinearLayout.HORIZONTAL);

        startButton = new Button(this);
        startButton.setText("감시 시작");
        startButton.setOnClickListener(v -> startMonitoring());
        monitorButtons.addView(startButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(this);
        stopButton.setText("감시 중지");
        stopButton.setOnClickListener(v -> stopMonitoring());
        monitorButtons.addView(stopButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(monitorButtons);

        monitorStatusText = new TextView(this);
        monitorStatusText.setPadding(dp(4), dp(4), dp(4), dp(6));
        root.addView(monitorStatusText);

        webView = new WebView(this);
        configureWebView(webView);
        webView.addJavascriptInterface(new InventoryBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                inspectButton.setEnabled(false);
                statusText.setText("로딩 중 · " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                boolean productPage = url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
                inspectButton.setEnabled(productPage);
                if (url != null && url.contains("nid.naver.com")) {
                    statusText.setText("네이버 로그인이 필요합니다. 로그인 후 상품 페이지로 돌아오세요.");
                } else {
                    statusText.setText("로드 완료 · " + view.getTitle() + "\n" + url);
                }
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
        resultText.setTextSize(12f);
        resultText.setPadding(dp(4), dp(6), dp(4), dp(6));
        resultText.setText("상품 페이지가 열린 뒤 '옵션 불러오기'를 누르세요.");
        resultScroll.addView(resultText);
        root.addView(resultScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(170)
        ));

        setContentView(root);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
    }

    private void openProduct() {
        String url = urlInput.getText().toString().trim();
        if (!url.startsWith("https://")) {
            resultText.setText("https:// SmartStore URL을 입력하세요.");
            return;
        }
        inspectButton.setEnabled(false);
        selectButton.setEnabled(false);
        latestOptions = new JSONArray();
        resultText.setText("페이지 로딩 중...");
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        inspectButton.setEnabled(false);
        statusText.setText("상품 API 확인 중...");
        resultText.setText("페이지 내부에서 옵션 재고를 조회하고 있습니다...");
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
                statusText.setText("옵션 조회 실패 · " + result.optString("error", "UNKNOWN"));
                resultText.setText(result.toString(2));
                return;
            }

            latestTitle = result.optString("title", "상품");
            latestOptions = result.optJSONArray("options");
            if (latestOptions == null) latestOptions = new JSONArray();
            selectButton.setEnabled(latestOptions.length() > 0);
            statusText.setText("옵션 조회 성공 · " + latestOptions.length() + "개");
            resultText.setText(formatInventory(result));
        } catch (Exception error) {
            statusText.setText("결과 파싱 실패");
            resultText.setText("raw:\n" + json + "\n\nerror:\n" + error);
        }
    }

    private void chooseOptions() {
        if (latestOptions.length() == 0) {
            Toast.makeText(this, "먼저 옵션을 불러오세요.", Toast.LENGTH_SHORT).show();
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
            labels[i] = label + (stock >= 0 ? " · 현재 " + stock + "개" : "");
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
                Toast.makeText(this, ids.size() + "개 옵션을 저장했습니다.", Toast.LENGTH_SHORT).show();
            })
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
            Toast.makeText(this, "옵션을 불러온 뒤 감시할 옵션을 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        String configuredUrl = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_URL, "");
        String currentUrl = urlInput.getText().toString().trim();
        if (!configuredUrl.equals(currentUrl)) {
            Toast.makeText(this, "URL이 바뀌었습니다. 옵션을 다시 불러와 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, String> labels = MonitorPrefs.selectedLabels(this);
        String title = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_TITLE, latestTitle);
        MonitorPrefs.saveConfig(this, currentUrl, title, ids, labels, selectedIntervalSeconds());
        Intent service = new Intent(this, MonitorService.class);
        startForegroundService(service);
        MonitorPrefs.setRunning(this, true);
        refreshMonitorUi();
        Toast.makeText(this, "재입고 감시를 시작했습니다.", Toast.LENGTH_SHORT).show();
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
            selectedText.setText("감시 옵션: 아직 선택하지 않음");
            return;
        }
        StringBuilder summary = new StringBuilder("감시 옵션 ").append(labels.size()).append("개\n");
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
        String status = prefs.getString(MonitorPrefs.KEY_LAST_STATUS, running ? "감시 시작 중" : "대기 중");
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        monitorStatusText.setText((running ? "🟢 감시 중" : "⚪ 감시 중지") + " · " + status);
    }

    private String formatInventory(JSONObject result) {
        StringBuilder output = new StringBuilder();
        output.append(result.optString("title", "상품")).append('\n');
        output.append("상품 재고: ").append(result.opt("stockQuantity")).append('\n');
        output.append("옵션 조합: ").append(result.optInt("optionCombinationCount", 0)).append("개\n\n");

        JSONArray options = result.optJSONArray("options");
        if (options == null || options.length() == 0) {
            output.append("옵션 조합 데이터 없음\n");
        } else {
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                output.append(option.optBoolean("available", false) ? "[재고] " : "[품절] ")
                    .append(optionLabel(option))
                    .append(" · 수량 ")
                    .append(option.isNull("stockQuantity") ? "?" : option.optInt("stockQuantity"))
                    .append('\n');
            }
        }
        output.append("\nAPI: ").append(result.optString("apiUrl", "-"));
        return output.toString();
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
        if (webView != null && webView.canGoBack()) webView.goBack();
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
