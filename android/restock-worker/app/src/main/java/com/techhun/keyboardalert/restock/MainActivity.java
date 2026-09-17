package com.techhun.keyboardalert.restock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
    private static final int PRODUCT_PREVIEW_LIMIT = 4;

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
    private Dialog optionDialog;

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
    private boolean optionLoadInProgress;
    private boolean showAllProducts;
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

        TextView title = text("재입고 알림", 30, TEXT, Typeface.BOLD);
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
                    handler.postDelayed(MainActivity.this::inspectInventory, 700L);
                }
            }
        });
        content.addView(webView, new LinearLayout.LayoutParams(dp(1), dp(1)));

        setContentView(scroll);
        renderProducts();
    }

    private void handleSessionAction() {
        if (hasNaverSession()) {
            ProductStore.disableAll(this);
            syncMonitorService();
            clearAppLogin();
        } else {
            openLoginForExistingProduct();
        }
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

    private boolean optionFlowBusy() {
        return optionLoadInProgress || (optionDialog != null && optionDialog.isShowing());
    }

    private void addProduct() {
        if (optionFlowBusy()) {
            toast("옵션을 불러오는 중이에요.");
            return;
        }
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
            toast("알림을 끈 뒤 옵션을 수정해주세요.");
            return;
        }
        if (optionFlowBusy()) {
            toast("옵션을 불러오는 중이에요.");
            return;
        }
        loadProductForEdit(product.optString("url"), product.optString("id"));
    }

    private void loadProductForEdit(String url, String id) {
        if (!url.startsWith("https://") || !url.contains("smartstore.naver.com")) {
            toast("SmartStore 상품 URL을 확인해주세요.");
            return;
        }
        if (optionFlowBusy()) return;

        optionLoadInProgress = true;
        pendingUrl = url;
        editingProductId = id;
        latestOptions = new JSONArray();
        latestTitle = "";
        latestApiUrl = "";
        latestChannelUid = "";
        latestProductNo = "";
        autoInspect = true;
        webView.stopLoading();
        webView.loadUrl(url);
    }

    private void inspectInventory() {
        if (!isProductUrl(webView.getUrl()) || !optionLoadInProgress) return;
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
    }

    private class InventoryBridge {
        @JavascriptInterface public void onResult(String json) {
            runOnUiThread(() -> handleInventory(json));
        }
    }

    private void handleInventory(String json) {
        if (!optionLoadInProgress) return;
        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok")) {
                int status = result.optInt("status", 0);
                boolean authFailure = status == 401 || status == 403 || !hasNaverSession();
                if (authFailure && !pendingUrl.isBlank()) {
                    launchLogin(pendingUrl);
                } else {
                    optionLoadInProgress = false;
                    clearPendingEdit();
                    toast("옵션 조회에 실패했어요.");
                }
                return;
            }

            latestTitle = result.optString("title", "SmartStore 상품");
            latestOptions = result.optJSONArray("options");
            latestApiUrl = result.optString("apiUrl", "");
            latestChannelUid = result.optString("channelUid", "");
            latestProductNo = result.optString("productNo", "");
            if (latestOptions == null || latestOptions.length() == 0) {
                optionLoadInProgress = false;
                clearPendingEdit();
                toast("선택 가능한 옵션이 없어요.");
                return;
            }
            showOptionPicker();
        } catch (Exception e) {
            optionLoadInProgress = false;
            clearPendingEdit();
            toast("상품 정보를 처리하지 못했어요.");
        }
    }

    private void showOptionPicker() {
        if (optionDialog != null && optionDialog.isShowing()) return;

        final String targetUrl = pendingUrl;
        final String targetEditingId = editingProductId;
        final String titleSnapshot = latestTitle;
        final String apiUrlSnapshot = latestApiUrl;
        final String channelUidSnapshot = latestChannelUid;
        final String productNoSnapshot = latestProductNo;
        final JSONArray optionsSnapshot = latestOptions;

        JSONObject existing = targetEditingId == null
            ? ProductStore.find(this, ProductStore.idFromUrl(targetUrl))
            : ProductStore.find(this, targetEditingId);
        Set<String> saved = new HashSet<>();
        if (existing != null) {
            JSONArray ids = existing.optJSONArray("selectedIds");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) saved.add(ids.optString(i));
            }
        }

        boolean[] checked = new boolean[optionsSnapshot.length()];
        for (int i = 0; i < optionsSnapshot.length(); i++) {
            JSONObject option = optionsSnapshot.optJSONObject(i);
            checked[i] = option != null && saved.contains(option.optString("id"));
        }

        Dialog dialog = new Dialog(this);
        optionDialog = dialog;
        optionLoadInProgress = false;
        boolean[] committed = {false};

        LinearLayout panel = surface(24, 20);
        panel.setBackground(roundRect(WHITE, 24));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, matchWrap());
        TextView dialogTitle = text("옵션 선택", 21, TEXT, Typeface.BOLD);
        header.addView(dialogTitle, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView close = text("닫기", 14, SUB, Typeface.BOLD);
        close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close);

        TextView productName = text(titleSnapshot, 13, SUB, Typeface.NORMAL);
        productName.setPadding(0, dp(5), 0, dp(14));
        panel.addView(productName);

        TextView selectedCount = text("", 13, BLUE, Typeface.BOLD);
        selectedCount.setPadding(0, 0, 0, dp(10));
        panel.addView(selectedCount);

        ScrollView scroll = new ScrollView(this);
        LinearLayout optionList = new LinearLayout(this);
        optionList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(optionList);
        panel.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(390)
        ));

        LinearLayout[] rows = new LinearLayout[optionsSnapshot.length()];
        TextView[] marks = new TextView[optionsSnapshot.length()];
        for (int i = 0; i < optionsSnapshot.length(); i++) {
            final int index = i;
            JSONObject option = optionsSnapshot.optJSONObject(i);
            int stock = option == null || option.isNull("stockQuantity")
                ? -1
                : option.optInt("stockQuantity", -1);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams rowLp = matchWrap();
            rowLp.bottomMargin = dp(8);
            optionList.addView(row, rowLp);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));
            left.addView(text(optionLabel(option), 14, TEXT, Typeface.BOLD));
            String stockLabel = stock > 0 ? "재고 " + stock + "개" : stock == 0 ? "품절" : "재고 확인 중";
            TextView stockView = text(stockLabel, 12, stock > 0 ? GREEN : SUB, Typeface.NORMAL);
            stockView.setPadding(0, dp(4), 0, 0);
            left.addView(stockView);

            TextView mark = text("", 19, BLUE, Typeface.BOLD);
            mark.setGravity(Gravity.CENTER);
            mark.setPadding(dp(12), 0, 0, 0);
            row.addView(mark);
            rows[i] = row;
            marks[i] = mark;
            applyOptionRowState(row, mark, checked[i]);

            row.setOnClickListener(v -> {
                checked[index] = !checked[index];
                applyOptionRowState(rows[index], marks[index], checked[index]);
                updateSelectedCount(selectedCount, checked);
            });
        }
        updateSelectedCount(selectedCount, checked);

        TextView save = text("저장", 15, Color.WHITE, Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setBackground(roundRect(BLUE, 14));
        save.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams saveLp = matchWrap();
        saveLp.topMargin = dp(8);
        panel.addView(save, saveLp);
        save.setOnClickListener(v -> {
            if (saveProductSelection(
                checked,
                targetUrl,
                targetEditingId,
                titleSnapshot,
                apiUrlSnapshot,
                channelUidSnapshot,
                productNoSnapshot,
                optionsSnapshot
            )) {
                committed[0] = true;
                dialog.dismiss();
            }
        });

        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            optionDialog = null;
            optionLoadInProgress = false;
            if (!committed[0]) clearPendingEdit();
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.32f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private void applyOptionRowState(LinearLayout row, TextView mark, boolean selected) {
        row.setBackground(roundRect(selected ? BLUE_SOFT : FIELD, 14));
        mark.setText(selected ? "✓" : "");
    }

    private void updateSelectedCount(TextView view, boolean[] checked) {
        int count = 0;
        for (boolean value : checked) if (value) count++;
        view.setText(count + "개 선택");
    }

    private boolean saveProductSelection(
        boolean[] checked,
        String targetUrl,
        String targetEditingId,
        String title,
        String apiUrl,
        String channelUid,
        String productNo,
        JSONArray options
    ) {
        JSONArray ids = new JSONArray();
        JSONObject labels = new JSONObject();
        try {
            for (int i = 0; i < checked.length; i++) {
                if (!checked[i]) continue;
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                String id = option.optString("id");
                if (id.isBlank()) continue;
                ids.put(id);
                labels.put(id, optionLabel(option));
            }
            if (ids.length() == 0) {
                toast("옵션을 하나 이상 선택해주세요.");
                return false;
            }
            if (targetUrl == null || targetUrl.isBlank()) return false;

            JSONObject product = new JSONObject();
            product.put("id", targetEditingId == null || targetEditingId.isBlank()
                ? ProductStore.idFromUrl(targetUrl)
                : targetEditingId);
            product.put("url", targetUrl);
            product.put("title", title);
            product.put("selectedIds", ids);
            product.put("selectedLabels", labels);
            product.put("apiUrl", apiUrl);
            product.put("channelUid", channelUid);
            product.put("productNo", productNo);
            ProductStore.upsert(this, product);
            clearPendingEdit();
            renderProducts();
            return true;
        } catch (Exception e) {
            toast("저장하지 못했어요.");
            return false;
        }
    }

    private void clearPendingEdit() {
        editingProductId = null;
        pendingUrl = "";
        latestOptions = new JSONArray();
        latestTitle = "";
        latestApiUrl = "";
        latestChannelUid = "";
        latestProductNo = "";
        autoInspect = false;
    }

    private void renderProducts() {
        if (productList == null) return;
        productList.removeAllViews();
        JSONArray products = ProductStore.list(this);
        if (products.length() == 0) {
            LinearLayout empty = surface(20, 18);
            TextView value = text("등록된 상품이 없어요", 15, SUB, Typeface.NORMAL);
            value.setGravity(Gravity.CENTER);
            empty.addView(value);
            productList.addView(empty, sectionParams());
            return;
        }

        int visibleCount = showAllProducts
            ? products.length()
            : Math.min(PRODUCT_PREVIEW_LIMIT, products.length());

        for (int i = 0; i < visibleCount; i++) {
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

            TextView badge = text(enabled ? "알림 켜짐" : "알림 꺼짐", 12, enabled ? GREEN : SUB, Typeface.BOLD);
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
                ? button("알림 끄기", RED, RED_SOFT)
                : button("알림 켜기", Color.WHITE, BLUE);
            toggle.setOnClickListener(v -> toggleProduct(product));
            actions.addView(toggle, rowParams(1f, 0));

            Button edit = softButton("옵션");
            edit.setOnClickListener(v -> editProduct(product));
            actions.addView(edit, rowParams(0.72f, 8));
        }

        if (products.length() > PRODUCT_PREVIEW_LIMIT) {
            int hidden = products.length() - PRODUCT_PREVIEW_LIMIT;
            String label = showAllProducts ? "접기" : hidden + "개 더보기";
            TextView more = text(label, 14, BLUE, Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(12));
            more.setOnClickListener(v -> {
                showAllProducts = !showAllProducts;
                renderProducts();
            });
            productList.addView(more, matchWrap());
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
            if (optionLoadInProgress) {
                optionLoadInProgress = false;
                clearPendingEdit();
            }
            return;
        }

        if (pendingEnableProductId != null) {
            ProductStore.setEnabled(this, pendingEnableProductId, true);
            pendingEnableProductId = null;
            syncMonitorService();
            renderProducts();
        }

        if (!pendingUrl.isBlank() && optionLoadInProgress) {
            autoInspect = true;
            webView.loadUrl(pendingUrl);
        }
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
        if (optionDialog != null && optionDialog.isShowing()) optionDialog.dismiss();
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
