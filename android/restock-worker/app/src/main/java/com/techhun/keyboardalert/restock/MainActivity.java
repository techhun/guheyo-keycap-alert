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
import android.webkit.WebStorage;
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
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_LOGIN = 9001;
    private static final int[] INTERVAL_VALUES = {15, 30, 60};
    private static final String[] INTERVAL_LABELS = {"15초", "30초", "60초"};
    private static final int BG = Color.rgb(247, 248, 250);
    private static final int WHITE = Color.WHITE;
    private static final int TEXT = Color.rgb(25, 31, 40);
    private static final int SUB = Color.rgb(139, 149, 161);
    private static final int BLUE = Color.rgb(49, 130, 246);
    private static final int BLUE_SOFT = Color.rgb(235, 244, 255);
    private static final int FIELD = Color.rgb(242, 244, 246);
    private static final int RED = Color.rgb(240, 68, 82);
    private static final int RED_SOFT = Color.rgb(255, 240, 242);
    private static final int GREEN = Color.rgb(20, 180, 110);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500L);
        }
    };

    private LinearLayout productList;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView[] intervalChips;
    private Button monitorButton;
    private WebView webView;

    private JSONArray latestOptions = new JSONArray();
    private String latestTitle = "";
    private String latestApiUrl = "";
    private String latestChannelUid = "";
    private String latestProductNo = "";
    private String pendingUrl = "";
    private String editingProductId = null;
    private boolean autoInspect;
    private boolean loginLaunching;
    private int intervalSeconds = 30;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);
        requestNotificationPermissionIfNeeded();
        ProductStore.migrateLegacyIfNeeded(this);
        intervalSeconds = MonitorPrefs.intervalSeconds(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(content);

        content.addView(text("KEYBOARD RESTOCK", 12, BLUE, Typeface.BOLD));
        TextView title = text("재입고 감시", 30, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        content.addView(title);
        TextView sub = text("필요한 옵션만 골라두면 이 기기에서 직접 확인해요.", 14, SUB, Typeface.NORMAL);
        sub.setPadding(0, dp(6), 0, dp(24));
        content.addView(sub);

        LinearLayout hero = surface(20, 18);
        content.addView(hero, sectionParams());
        hero.addView(text("현재 상태", 13, SUB, Typeface.NORMAL));
        statusTitle = text("감시 중지", 24, TEXT, Typeface.BOLD);
        statusTitle.setPadding(0, dp(5), 0, 0);
        hero.addView(statusTitle);
        statusDetail = text("상품을 추가하고 감시를 시작하세요.", 13, SUB, Typeface.NORMAL);
        statusDetail.setPadding(0, dp(7), 0, 0);
        hero.addView(statusDetail);

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setPadding(dp(2), dp(18), dp(2), dp(10));
        content.addView(listHeader);
        listHeader.addView(text("감시 상품", 19, TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = text("+ 상품 추가", 15, BLUE, Typeface.BOLD);
        add.setPadding(dp(12), dp(8), 0, dp(8));
        add.setOnClickListener(v -> addProduct());
        listHeader.addView(add);

        productList = new LinearLayout(this);
        productList.setOrientation(LinearLayout.VERTICAL);
        content.addView(productList);

        sectionHeading(content, "감시 설정");
        LinearLayout settings = surface(20, 18);
        content.addView(settings, sectionParams());
        settings.addView(text("조회 주기", 13, SUB, Typeface.NORMAL));
        TextView intervalHint = text("상품이 여러 개면 이 주기 안에서 요청을 나눠서 확인해요.", 12, SUB, Typeface.NORMAL);
        intervalHint.setPadding(0, dp(4), 0, 0);
        settings.addView(intervalHint);
        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setPadding(0, dp(10), 0, 0);
        settings.addView(intervalRow, matchWrap());
        intervalChips = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int seconds = INTERVAL_VALUES[i];
            TextView chip = text(INTERVAL_LABELS[i], 14, SUB, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                if (isRunning()) return;
                intervalSeconds = seconds;
                updateIntervalChips();
            });
            intervalChips[i] = chip;
            intervalRow.addView(chip, rowParams(1f, i == 0 ? 0 : 8));
        }
        updateIntervalChips();

        monitorButton = primaryButton("감시 시작");
        monitorButton.setOnClickListener(v -> { if (isRunning()) stopMonitoring(); else startMonitoring(); });
        settings.addView(monitorButton, topParams(16));

        sectionHeading(content, "로그인 관리");
        LinearLayout loginSettings = surface(20, 18);
        content.addView(loginSettings, sectionParams());
        loginSettings.addView(text("네이버 세션", 16, TEXT, Typeface.BOLD));
        TextView loginHint = text("로그인이 풀리면 전체화면 로그인으로 전환돼요. 아래 삭제는 이 앱의 로그인만 지워요.", 13, SUB, Typeface.NORMAL);
        loginHint.setPadding(0, dp(6), 0, dp(12));
        loginSettings.addView(loginHint);
        LinearLayout loginActions = new LinearLayout(this);
        loginSettings.addView(loginActions, matchWrap());
        Button relogin = softButton("다시 로그인");
        relogin.setOnClickListener(v -> openLoginForExistingProduct());
        loginActions.addView(relogin, rowParams(1f, 0));
        Button clearLogin = softRedButton("로그인 정보 삭제");
        clearLogin.setOnClickListener(v -> confirmClearLogin());
        loginActions.addView(clearLogin, rowParams(1f, 8));

        // 내부 조회 엔진. 사용자 화면에는 표시하지 않는다.
        webView = new WebView(this);
        configureWebView(webView);
        webView.setVisibility(View.INVISIBLE);
        webView.addJavascriptInterface(new InventoryBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (isLoginUrl(url)) {
                    if (!pendingUrl.isBlank()) launchLogin(pendingUrl);
                    return;
                }
                if (isProductUrl(url) && autoInspect) {
                    autoInspect = false;
                    handler.postDelayed(MainActivity.this::inspectInventory, 900);
                }
            }
        });
        LinearLayout.LayoutParams hiddenParams = new LinearLayout.LayoutParams(dp(1), dp(1));
        content.addView(webView, hiddenParams);

        setContentView(scroll);
        renderProducts();
        refreshStatus();
    }

    private void addProduct() {
        if (isRunning()) { toast("감시를 중지한 뒤 상품을 추가해주세요."); return; }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://m.smartstore.naver.com/.../products/...");
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
            .setTitle("상품 추가")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("다음", (d, w) -> loadProductForEdit(input.getText().toString().trim(), null))
            .show();
    }

    private void editProduct(JSONObject product) {
        if (isRunning()) { toast("감시를 중지한 뒤 수정해주세요."); return; }
        loadProductForEdit(product.optString("url"), product.optString("id"));
    }

    private void loadProductForEdit(String url, String id) {
        if (!url.startsWith("https://") || !url.contains("smartstore.naver.com")) { toast("SmartStore 상품 URL을 확인해주세요."); return; }
        pendingUrl = url;
        editingProductId = id;
        latestOptions = new JSONArray();
        latestApiUrl = "";
        latestChannelUid = "";
        latestProductNo = "";
        autoInspect = true;
        webView.loadUrl(url);
        toast("상품 옵션을 불러오는 중이에요.");
    }

    private void inspectInventory() {
        if (!isProductUrl(webView.getUrl())) return;
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
    }

    private class InventoryBridge {
        @JavascriptInterface public void onResult(String json) { runOnUiThread(() -> handleInventory(json)); }
    }

    private void handleInventory(String json) {
        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok")) {
                String error = result.optString("error", "UNKNOWN");
                if ("PRODUCT_API_FAILED".equals(error) && !pendingUrl.isBlank()) launchLogin(pendingUrl);
                else toast("옵션 조회에 실패했어요. 다시 시도해주세요.");
                return;
            }
            latestTitle = result.optString("title", "SmartStore 상품");
            latestOptions = result.optJSONArray("options");
            latestApiUrl = result.optString("apiUrl", "");
            latestChannelUid = result.optString("channelUid", "");
            latestProductNo = result.optString("productNo", "");
            if (latestOptions == null || latestOptions.length() == 0) { toast("선택 가능한 옵션이 없어요."); return; }
            showOptionPicker();
        } catch (Exception e) {
            toast("상품 정보를 처리하지 못했어요.");
        }
    }

    private void showOptionPicker() {
        JSONObject existing = editingProductId == null ? ProductStore.find(this, ProductStore.idFromUrl(pendingUrl)) : ProductStore.find(this, editingProductId);
        Set<String> saved = new HashSet<>();
        if (existing != null) {
            JSONArray ids = existing.optJSONArray("selectedIds");
            if (ids != null) for (int i = 0; i < ids.length(); i++) saved.add(ids.optString(i));
        }
        String[] rows = new String[latestOptions.length()];
        boolean[] checked = new boolean[latestOptions.length()];
        for (int i = 0; i < latestOptions.length(); i++) {
            JSONObject option = latestOptions.optJSONObject(i);
            String id = option == null ? "" : option.optString("id");
            int stock = option == null || option.isNull("stockQuantity") ? -1 : option.optInt("stockQuantity", -1);
            rows[i] = optionLabel(option) + "  ·  " + (stock > 0 ? stock + "개" : stock == 0 ? "품절" : "재고 ?");
            checked[i] = saved.contains(id);
        }

        new AlertDialog.Builder(this)
            .setTitle(latestTitle)
            .setMultiChoiceItems(rows, checked, (d, which, value) -> checked[which] = value)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", (d, w) -> saveProductSelection(checked))
            .show();
    }

    private void saveProductSelection(boolean[] checked) {
        JSONArray ids = new JSONArray();
        JSONObject labels = new JSONObject();
        try {
            for (int i = 0; i < checked.length; i++) {
                if (!checked[i]) continue;
                JSONObject option = latestOptions.optJSONObject(i);
                if (option == null) continue;
                String id = option.optString("id");
                if (id.isBlank()) continue;
                ids.put(id);
                labels.put(id, optionLabel(option));
            }
            if (ids.length() == 0) { toast("최소 한 개 옵션을 선택해주세요."); return; }
            JSONObject product = new JSONObject();
            product.put("id", ProductStore.idFromUrl(pendingUrl));
            product.put("url", pendingUrl);
            product.put("title", latestTitle);
            product.put("selectedIds", ids);
            product.put("selectedLabels", labels);
            product.put("apiUrl", latestApiUrl);
            product.put("channelUid", latestChannelUid);
            product.put("productNo", latestProductNo);
            ProductStore.upsert(this, product);
            editingProductId = null;
            pendingUrl = "";
            renderProducts();
            toast("감시 상품에 저장했어요.");
        } catch (Exception e) {
            toast("저장하지 못했어요.");
        }
    }

    private void renderProducts() {
        productList.removeAllViews();
        JSONArray products = ProductStore.list(this);
        if (products.length() == 0) {
            LinearLayout empty = surface(20, 18);
            empty.addView(text("아직 감시할 상품이 없어요", 16, TEXT, Typeface.BOLD));
            TextView s = text("오른쪽 위의 + 상품 추가에서 시작하세요.", 13, SUB, Typeface.NORMAL);
            s.setPadding(0, dp(5), 0, 0);
            empty.addView(s);
            productList.addView(empty, sectionParams());
            return;
        }
        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null) continue;
            LinearLayout card = surface(20, 18);
            productList.addView(card, sectionParams());
            card.addView(text(product.optString("title", "SmartStore 상품"), 17, TEXT, Typeface.BOLD));
            Map<String, String> labels = labelMap(product.optJSONObject("selectedLabels"));
            String optionSummary;
            if (labels.isEmpty()) optionSummary = "선택 옵션 없음";
            else if (labels.size() == 1) optionSummary = labels.values().iterator().next();
            else optionSummary = labels.values().iterator().next() + " 외 " + (labels.size() - 1) + "개";
            TextView options = text(optionSummary, 13, SUB, Typeface.NORMAL);
            options.setPadding(0, dp(6), 0, 0);
            card.addView(options);
            String last = product.optString("lastStatus", product.optString("apiUrl", "").isBlank() ? "첫 감시 때 빠른 조회 정보를 준비해요" : "대기 중");
            TextView state = text(last, 12, SUB, Typeface.NORMAL);
            state.setPadding(0, dp(7), 0, dp(12));
            card.addView(state);
            LinearLayout actions = new LinearLayout(this);
            card.addView(actions, matchWrap());
            Button edit = softButton("옵션 수정");
            edit.setOnClickListener(v -> editProduct(product));
            actions.addView(edit, rowParams(1f, 0));
            Button delete = softRedButton("삭제");
            delete.setOnClickListener(v -> confirmDelete(product));
            actions.addView(delete, rowParams(0.55f, 8));
        }
    }

    private void confirmDelete(JSONObject product) {
        if (isRunning()) { toast("감시를 중지한 뒤 삭제해주세요."); return; }
        new AlertDialog.Builder(this)
            .setTitle("상품 삭제")
            .setMessage(product.optString("title") + "\n감시 목록에서 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제", (d, w) -> { ProductStore.remove(this, product.optString("id")); renderProducts(); })
            .show();
    }

    private void openLoginForExistingProduct() {
        JSONArray products = ProductStore.list(this);
        if (products.length() == 0) { toast("상품을 하나 추가하면 로그인 상태를 확인할 수 있어요."); return; }
        JSONObject first = products.optJSONObject(0);
        if (first == null) return;
        launchLogin(first.optString("url"));
    }

    private void launchLogin(String targetUrl) {
        if (loginLaunching || targetUrl == null || targetUrl.isBlank()) return;
        loginLaunching = true;
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra(LoginActivity.EXTRA_TARGET_URL, targetUrl);
        startActivityForResult(intent, REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN) return;
        loginLaunching = false;
        if (resultCode == RESULT_OK) {
            toast("로그인됐어요.");
            if (!pendingUrl.isBlank()) {
                autoInspect = true;
                webView.loadUrl(pendingUrl);
            }
        }
    }

    private void confirmClearLogin() {
        if (isRunning()) { toast("감시를 중지한 뒤 로그인 정보를 삭제해주세요."); return; }
        new AlertDialog.Builder(this)
            .setTitle("앱 로그인 정보 삭제")
            .setMessage("Keyboard Restock 안에 저장된 네이버 로그인만 삭제할까요?\nChrome이나 삼성 인터넷 로그인에는 영향이 없어요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제", (d, w) -> clearAppLogin())
            .show();
    }

    private void clearAppLogin() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(value -> {
            cookies.flush();
            WebStorage.getInstance().deleteAllData();
            if (webView != null) {
                webView.clearCache(true);
                webView.clearHistory();
                webView.loadUrl("about:blank");
            }
            runOnUiThread(() -> toast("이 앱의 네이버 로그인 정보를 삭제했어요."));
        });
    }

    private void startMonitoring() {
        JSONArray products = ProductStore.list(this);
        if (products.length() == 0) { toast("감시할 상품을 먼저 추가해주세요."); return; }
        MonitorPrefs.prefs(this).edit().putInt(MonitorPrefs.KEY_INTERVAL, intervalSeconds).apply();
        MonitorPrefs.setRunning(this, true);
        startForegroundService(new Intent(this, MonitorService.class));
        refreshStatus();
        toast(products.length() + "개 상품 감시를 시작했어요.");
    }

    private void stopMonitoring() {
        startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
        MonitorPrefs.setRunning(this, false);
        refreshStatus();
        toast("감시를 중지했어요.");
    }

    private void refreshStatus() {
        boolean running = isRunning();
        JSONArray products = ProductStore.list(this);
        statusTitle.setText(running ? "감시 중" : "감시 중지");
        statusTitle.setTextColor(running ? GREEN : TEXT);
        String status = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_LAST_STATUS, running ? "감시 준비 중" : "대기 중");
        long last = MonitorPrefs.prefs(this).getLong(MonitorPrefs.KEY_LAST_CHECK, 0);
        String detail = products.length() + "개 상품 · " + status;
        if (last > 0) detail += " · " + new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date(last));
        statusDetail.setText(detail);
        monitorButton.setText(running ? "감시 중지" : "감시 시작");
        monitorButton.setTextColor(running ? RED : Color.WHITE);
        monitorButton.setBackground(roundRect(running ? RED_SOFT : BLUE, 12));
        intervalSeconds = running ? MonitorPrefs.intervalSeconds(this) : intervalSeconds;
        updateIntervalChips();
    }

    private boolean isRunning() { return MonitorPrefs.prefs(this).getBoolean(MonitorPrefs.KEY_RUNNING, false); }

    private void updateIntervalChips() {
        if (intervalChips == null) return;
        for (int i = 0; i < intervalChips.length; i++) {
            boolean selected = INTERVAL_VALUES[i] == intervalSeconds;
            intervalChips[i].setTextColor(selected ? BLUE : SUB);
            intervalChips[i].setBackground(roundRect(selected ? BLUE_SOFT : FIELD, 12));
            intervalChips[i].setAlpha(isRunning() ? 0.5f : 1f);
        }
    }

    private Map<String, String> labelMap(JSONObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object == null) return result;
        var keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); result.put(key, object.optString(key, key)); }
        return result;
    }

    static String optionLabel(JSONObject option) {
        if (option == null) return "옵션";
        StringBuilder label = new StringBuilder();
        for (String key : new String[]{"optionName1", "optionName2", "optionName3"}) {
            String value = option.optString(key, "");
            if (value.isBlank() || "null".equals(value)) continue;
            if (label.length() > 0) label.append(" / ");
            label.append(value);
        }
        return label.length() == 0 ? option.optString("id", "옵션") : label.toString();
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv)", ")").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
    }

    private boolean isProductUrl(String url) { return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/"); }
    private boolean isLoginUrl(String url) { return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login")); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private TextView text(String value, float size, int color, int style) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setTypeface(null, style); return v; }
    private LinearLayout surface(int radius, int padding) { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); v.setBackground(roundRect(WHITE, radius)); return v; }
    private GradientDrawable roundRect(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams sectionParams() { LinearLayout.LayoutParams p = matchWrap(); p.bottomMargin = dp(8); return p; }
    private LinearLayout.LayoutParams topParams(int top) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(top); return p; }
    private LinearLayout.LayoutParams rowParams(float weight, int left) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), weight); p.leftMargin = dp(left); return p; }
    private void sectionHeading(LinearLayout parent, String value) { TextView t = text(value, 19, TEXT, Typeface.BOLD); t.setPadding(dp(2), dp(18), 0, dp(10)); parent.addView(t); }
    private Button button(String label, int textColor, int bg) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(null, Typeface.BOLD); b.setTextColor(textColor); b.setBackground(roundRect(bg, 12)); b.setMinWidth(0); b.setMinHeight(0); return b; }
    private Button primaryButton(String label) { return button(label, Color.WHITE, BLUE); }
    private Button softButton(String label) { return button(label, TEXT, FIELD); }
    private Button softRedButton(String label) { return button(label, RED, RED_SOFT); }
    private void requestNotificationPermissionIfNeeded() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001); }

    @Override protected void onResume() { super.onResume(); renderProducts(); handler.removeCallbacks(statusRefresh); handler.post(statusRefresh); }
    @Override protected void onPause() { handler.removeCallbacks(statusRefresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (webView != null) { webView.removeJavascriptInterface("RestockBridge"); webView.stopLoading(); webView.destroy(); } super.onDestroy(); }
}
