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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_LOGIN = 9001;
    private static final int[] INTERVAL_VALUES = {15, 30, 60};
    private static final String[] INTERVAL_LABELS = {"15초", "30초", "60초"};
    private static final String SMARTSTORE_HOME = "https://m.smartstore.naver.com/";

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
    private static final int GREEN_SOFT = Color.rgb(232, 249, 241);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            renderProducts();
            refreshSessionButton();
            handler.postDelayed(this, 2000L);
        }
    };

    private LinearLayout productList;
    private TextView sessionButton;
    private TextView[] intervalChips;
    private WebView webView;

    private JSONArray latestOptions = new JSONArray();
    private String latestTitle = "";
    private String latestApiUrl = "";
    private String latestChannelUid = "";
    private String latestProductNo = "";
    private String pendingUrl = "";
    private String editingProductId = null;
    private String pendingEnableProductId = null;
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

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(topRow, matchWrap());

        TextView title = text("재입고 감시", 30, TEXT, Typeface.BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));

        sessionButton = text("로그인", 14, BLUE, Typeface.BOLD);
        sessionButton.setGravity(Gravity.CENTER);
        sessionButton.setPadding(dp(15), dp(9), dp(15), dp(9));
        sessionButton.setOnClickListener(v -> handleSessionAction());
        topRow.addView(sessionButton);
        refreshSessionButton();

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, dp(18), 0, dp(10));
        content.addView(addRow, matchWrap());
        TextView add = text("+ 상품 추가", 15, BLUE, Typeface.BOLD);
        add.setPadding(dp(12), dp(8), 0, dp(8));
        add.setOnClickListener(v -> addProduct());
        addRow.addView(add);

        productList = new LinearLayout(this);
        productList.setOrientation(LinearLayout.VERTICAL);
        content.addView(productList);

        LinearLayout settings = surface(20, 18);
        LinearLayout.LayoutParams settingsParams = sectionParams();
        settingsParams.topMargin = dp(10);
        content.addView(settings, settingsParams);
        settings.addView(text("조회 주기", 14, TEXT, Typeface.BOLD));

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setPadding(0, dp(12), 0, 0);
        settings.addView(intervalRow, matchWrap());
        intervalChips = new TextView[INTERVAL_VALUES.length];
        for (int i = 0; i < INTERVAL_VALUES.length; i++) {
            final int seconds = INTERVAL_VALUES[i];
            TextView chip = text(INTERVAL_LABELS[i], 14, SUB, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                intervalSeconds = seconds;
                MonitorPrefs.prefs(this).edit().putInt(MonitorPrefs.KEY_INTERVAL, seconds).apply();
                updateIntervalChips();
            });
            intervalChips[i] = chip;
            intervalRow.addView(chip, rowParams(1f, i == 0 ? 0 : 8));
        }
        updateIntervalChips();

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
                    handler.postDelayed(MainActivity.this::inspectInventory, 900L);
                }
            }
        });
        content.addView(webView, new LinearLayout.LayoutParams(dp(1), dp(1)));

        setContentView(scroll);
        renderProducts();
    }

    private void handleSessionAction() {
        if (hasNaverSession()) confirmClearLogin();
        else openLoginForExistingProduct();
    }

    private boolean hasNaverSession() {
        CookieManager manager = CookieManager.getInstance();
        String cookie = manager.getCookie("https://naver.com");
        if (cookie == null || cookie.isBlank()) cookie = manager.getCookie(SMARTSTORE_HOME);
        if (cookie == null) return false;
        return cookie.contains("NID_SES=") || cookie.contains("NID_AUT=");
    }

    private void refreshSessionButton() {
        if (sessionButton == null) return;
        boolean loggedIn = hasNaverSession();
        sessionButton.setText(loggedIn ? "로그아웃" : "로그인");
        sessionButton.setTextColor(loggedIn ? SUB : BLUE);
        sessionButton.setBackground(roundRect(loggedIn ? FIELD : BLUE_SOFT, 14));
    }

    private void addProduct() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("SmartStore 상품 URL");
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
            .setTitle("상품 추가")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("다음", (d, w) -> loadProductForEdit(input.getText().toString().trim(), null))
            .show();
    }

    private void editProduct(JSONObject product) {
        if (product.optBoolean("enabled", false)) {
            toast("감시를 중지한 뒤 옵션을 수정해주세요.");
            return;
        }
        loadProductForEdit(product.optString("url"), product.optString("id"));
    }

    private void loadProductForEdit(String url, String id) {
        if (!url.startsWith("https://") || !url.contains("smartstore.naver.com")) {
            toast("SmartStore 상품 URL을 확인해주세요.");
            return;
        }
        pendingUrl = url;
        editingProductId = id;
        latestOptions = new JSONArray();
        latestApiUrl = "";
        latestChannelUid = "";
        latestProductNo = "";
        autoInspect = true;
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        if (!isProductUrl(webView.getUrl())) return;
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
    }

    private class InventoryBridge {
        @JavascriptInterface public void onResult(String json) {
            runOnUiThread(() -> handleInventory(json));
        }
    }

    private void handleInventory(String json) {
        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok")) {
                String error = result.optString("error", "UNKNOWN");
                if ("PRODUCT_API_FAILED".equals(error) && !pendingUrl.isBlank()) launchLogin(pendingUrl);
                else toast("옵션 조회에 실패했어요.");
                return;
            }
            latestTitle = result.optString("title", "SmartStore 상품");
            latestOptions = result.optJSONArray("options");
            latestApiUrl = result.optString("apiUrl", "");
            latestChannelUid = result.optString("channelUid", "");
            latestProductNo = result.optString("productNo", "");
            if (latestOptions == null || latestOptions.length() == 0) {
                toast("선택 가능한 옵션이 없어요.");
                return;
            }
            showOptionPicker();
        } catch (Exception e) {
            toast("상품 정보를 처리하지 못했어요.");
        }
    }

    private void showOptionPicker() {
        JSONObject existing = editingProductId == null
            ? ProductStore.find(this, ProductStore.idFromUrl(pendingUrl))
            : ProductStore.find(this, editingProductId);
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
            int stock = option == null || option.isNull("stockQuantity")
                ? -1
                : option.optInt("stockQuantity", -1);
            rows[i] = optionLabel(option) + "  ·  "
                + (stock > 0 ? stock + "개" : stock == 0 ? "품절" : "재고 ?");
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
            if (ids.length() == 0) {
                toast("최소 한 개 옵션을 선택해주세요.");
                return;
            }
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
        } catch (Exception e) {
            toast("저장하지 못했어요.");
        }
    }

    private void renderProducts() {
        if (productList == null) return;
        productList.removeAllViews();
        JSONArray products = ProductStore.list(this);
        if (products.length() == 0) {
            LinearLayout empty = surface(20, 18);
            TextView value = text("감시할 상품이 없어요", 15, SUB, Typeface.NORMAL);
            value.setGravity(Gravity.CENTER);
            empty.addView(value);
            productList.addView(empty, sectionParams());
            return;
        }

        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null) continue;
            boolean enabled = product.optBoolean("enabled", false);

            LinearLayout card = surface(20, 18);
            productList.addView(card, sectionParams());

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.TOP);
            card.addView(titleRow, matchWrap());

            TextView name = text(product.optString("title", "SmartStore 상품"), 17, TEXT, Typeface.BOLD);
            titleRow.addView(name, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));

            TextView badge = text(enabled ? "감시 중" : "중지", 12, enabled ? GREEN : SUB, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(10), dp(6), dp(10), dp(6));
            badge.setBackground(roundRect(enabled ? GREEN_SOFT : FIELD, 12));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.leftMargin = dp(8);
            titleRow.addView(badge, badgeParams);

            TextView delete = text("삭제", 12, RED, Typeface.BOLD);
            delete.setPadding(dp(10), dp(6), 0, dp(6));
            delete.setOnClickListener(v -> confirmDelete(product));
            titleRow.addView(delete);

            Map<String, String> labels = labelMap(product.optJSONObject("selectedLabels"));
            String optionSummary;
            if (labels.isEmpty()) optionSummary = "선택 옵션 없음";
            else if (labels.size() == 1) optionSummary = labels.values().iterator().next();
            else optionSummary = labels.values().iterator().next() + " 외 " + (labels.size() - 1) + "개";
            TextView options = text(optionSummary, 13, SUB, Typeface.NORMAL);
            options.setPadding(0, dp(7), 0, 0);
            card.addView(options);

            String last = product.optString("lastStatus", "");
            if (enabled && !last.isBlank()) {
                TextView state = text(last, 12, SUB, Typeface.NORMAL);
                state.setPadding(0, dp(7), 0, 0);
                card.addView(state);
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(0, dp(14), 0, 0);
            card.addView(actions, matchWrap());

            Button toggle = enabled
                ? button("감시 중지", RED, RED_SOFT)
                : button("감시 시작", Color.WHITE, BLUE);
            toggle.setOnClickListener(v -> toggleProduct(product));
            actions.addView(toggle, rowParams(1f, 0));

            Button edit = softButton("옵션 수정");
            edit.setOnClickListener(v -> editProduct(product));
            actions.addView(edit, rowParams(1f, 8));
        }
    }

    private void toggleProduct(JSONObject product) {
        String id = product.optString("id", "");
        if (id.isBlank()) return;
        boolean enable = !product.optBoolean("enabled", false);

        if (enable && !hasNaverSession()) {
            pendingEnableProductId = id;
            launchLogin(product.optString("url", SMARTSTORE_HOME));
            return;
        }

        ProductStore.setEnabled(this, id, enable);
        syncMonitorService();
        renderProducts();
    }

    private void syncMonitorService() {
        int enabled = ProductStore.enabledCount(this);
        if (enabled <= 0) {
            startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
            MonitorPrefs.setRunning(this, false);
            return;
        }
        MonitorPrefs.prefs(this).edit().putInt(MonitorPrefs.KEY_INTERVAL, intervalSeconds).apply();
        if (!isRunning()) startForegroundService(new Intent(this, MonitorService.class));
    }

    private void confirmDelete(JSONObject product) {
        new AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제", (d, w) -> {
                ProductStore.remove(this, product.optString("id"));
                syncMonitorService();
                renderProducts();
            })
            .show();
    }

    private void openLoginForExistingProduct() {
        JSONArray products = ProductStore.list(this);
        String target = SMARTSTORE_HOME;
        if (products.length() > 0) {
            JSONObject first = products.optJSONObject(0);
            if (first != null && !first.optString("url").isBlank()) target = first.optString("url");
        }
        launchLogin(target);
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
        refreshSessionButton();
        if (resultCode != RESULT_OK) {
            pendingEnableProductId = null;
            return;
        }

        if (pendingEnableProductId != null) {
            ProductStore.setEnabled(this, pendingEnableProductId, true);
            pendingEnableProductId = null;
            syncMonitorService();
            renderProducts();
        }

        if (!pendingUrl.isBlank()) {
            autoInspect = true;
            webView.loadUrl(pendingUrl);
        }
    }

    private void confirmClearLogin() {
        new AlertDialog.Builder(this)
            .setTitle("로그아웃할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("로그아웃", (d, w) -> {
                ProductStore.disableAll(this);
                syncMonitorService();
                clearAppLogin();
            })
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
            runOnUiThread(() -> {
                refreshSessionButton();
                renderProducts();
            });
        });
    }

    private boolean isRunning() {
        return MonitorPrefs.prefs(this).getBoolean(MonitorPrefs.KEY_RUNNING, false);
    }

    private void updateIntervalChips() {
        if (intervalChips == null) return;
        for (int i = 0; i < intervalChips.length; i++) {
            boolean selected = INTERVAL_VALUES[i] == intervalSeconds;
            intervalChips[i].setTextColor(selected ? BLUE : SUB);
            intervalChips[i].setBackground(roundRect(selected ? BLUE_SOFT : FIELD, 12));
        }
    }

    private Map<String, String> labelMap(JSONObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object == null) return result;
        var keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, object.optString(key, key));
        }
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
        settings.setUserAgentString(settings.getUserAgentString()
            .replace("; wv)", ")")
            .replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(null, style);
        return view;
    }

    private LinearLayout surface(int radius, int padding) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        view.setBackground(roundRect(WHITE, radius));
        return view;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams rowParams(float weight, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), weight);
        params.leftMargin = dp(left);
        return params;
    }

    private Button button(String label, int textColor, int backgroundColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setBackground(roundRect(backgroundColor, 12));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setStateListAnimator(null);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private Button softButton(String label) {
        return button(label, TEXT, FIELD);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (ProductStore.enabledCount(this) > 0 && hasNaverSession() && !isRunning()) {
            startForegroundService(new Intent(this, MonitorService.class));
        }
        renderProducts();
        refreshSessionButton();
        handler.removeCallbacks(statusRefresh);
        handler.post(statusRefresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
