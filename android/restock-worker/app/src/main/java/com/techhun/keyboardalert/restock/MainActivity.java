package com.techhun.keyboardalert.restock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_LOGIN = 9001;
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

    private boolean firstResume = true;
    private boolean refreshProductsOnResume;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            renderProducts();
            refreshSessionButton();
            handler.postDelayed(this, 2500L);
        }
    };
    private final Runnable resumeRefresh = () -> {
        resumeMonitorIfNeeded();
        if (refreshProductsOnResume) renderProducts();
        refreshSessionButton();
        handler.removeCallbacks(statusRefresh);
        handler.postDelayed(statusRefresh, 2500L);
    };

    private LinearLayout productList;
    private ScrollView productScroll;
    private LinearLayout scrollDots;
    private int renderedProductCount;
    private int dotWindowStart = -1;
    private TextView sessionButton;
    private WebView webView;
    private Dialog optionDialog;
    private Dialog addDialog;

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

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(false);
        requestNotificationPermissionIfNeeded();
        ProductStore.migrateLegacyIfNeeded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
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
            view.setPadding(dp(20), top + dp(10), dp(20), bottom + dp(10));
            return insets;
        });

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(topRow, matchWrap());

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        topRow.addView(appIcon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        View spacer = new View(this);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        sessionButton = text("로그인", 14, BLUE, Typeface.BOLD);
        sessionButton.setGravity(Gravity.CENTER);
        sessionButton.setPadding(dp(14), dp(9), dp(14), dp(9));
        sessionButton.setOnClickListener(v -> handleSessionAction());
        Motion.press(sessionButton);
        topRow.addView(sessionButton);

        ImageView settings = new ImageView(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setColorFilter(TEXT);
        settings.setPadding(dp(10), dp(10), dp(10), dp(10));
        settings.setOnClickListener(v ->
            Motion.push(this, new Intent(this, SettingsActivity.class))
        );
        Motion.press(settings);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        settingsLp.leftMargin = dp(4);
        topRow.addView(settings, settingsLp);
        refreshSessionButton();

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, dp(10), 0, dp(8));
        root.addView(addRow, matchWrap());
        TextView add = text("+ 상품 추가", 15, BLUE, Typeface.BOLD);
        add.setPadding(dp(12), dp(8), 0, dp(8));
        add.setOnClickListener(v -> showAddProductDialog());
        Motion.press(add);
        addRow.addView(add);

        FrameLayout productArea = new FrameLayout(this);
        root.addView(productArea, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        productScroll = new ScrollView(this);
        productScroll.setFillViewport(true);
        productScroll.setClipToPadding(false);
        productScroll.setPadding(0, 0, dp(18), dp(12));
        productScroll.setVerticalScrollBarEnabled(false);
        productScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        productArea.addView(productScroll, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        productList = new LinearLayout(this);
        productList.setOrientation(LinearLayout.VERTICAL);
        productScroll.addView(productList, matchWrap());

        scrollDots = new LinearLayout(this);
        scrollDots.setOrientation(LinearLayout.VERTICAL);
        scrollDots.setGravity(Gravity.CENTER);
        scrollDots.setVisibility(View.GONE);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
            dp(24),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END | Gravity.CENTER_VERTICAL
        );
        productArea.addView(scrollDots, dotsLp);

        productScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) ->
            updateCardDots()
        );
        productArea.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            updateCardDots()
        );

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
        root.addView(webView, new LinearLayout.LayoutParams(dp(1), dp(1)));

        setContentView(root);
        root.requestApplyInsets();
        Motion.enter(topRow, 0L);
        Motion.enter(addRow, 45L);
        renderProducts(true);
    }

    private void handleSessionAction() {
        if (!hasNaverSession()) {
            openLoginForExistingProduct();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("로그아웃할까요?")
            .setMessage("켜진 재입고 알림도 함께 꺼져요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("로그아웃", (d, w) -> {
                ProductStore.disableAll(this);
                syncMonitorService();
                clearAppLogin();
            })
            .show();
    }

    private boolean hasNaverSession() {
        return SessionState.hasNaverSession();
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

    private void showAddProductDialog() {
        if (optionFlowBusy()) {
            toast("옵션을 불러오는 중이에요.");
            return;
        }
        if (addDialog != null && addDialog.isShowing()) return;

        Dialog dialog = new Dialog(this);
        addDialog = dialog;
        LinearLayout panel = surface(24, 20);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, matchWrap());
        header.addView(text("상품 추가", 21, TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView close = text("닫기", 14, SUB, Typeface.BOLD);
        close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> Motion.dismissDialog(dialog, panel));
        Motion.press(close);
        header.addView(close);

        TextView label = text("SmartStore 상품 주소", 13, SUB, Typeface.NORMAL);
        label.setPadding(0, dp(16), 0, dp(8));
        panel.addView(label);

        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(14), 0, dp(8), 0);
        field.setBackground(roundRect(FIELD, 14));
        panel.addView(field, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(TEXT);
        input.setHintTextColor(SUB);
        input.setHint("https://smartstore.naver.com/...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, 0, dp(8), 0);
        field.addView(input, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ));

        String clipboardUrl = readClipboardSmartStoreUrl();
        ImageView paste = new ImageView(this);
        paste.setImageResource(R.drawable.ic_clipboard);
        paste.setPadding(dp(9), dp(9), dp(9), dp(9));
        paste.setColorFilter(clipboardUrl.isBlank() ? SUB : BLUE);
        paste.setAlpha(clipboardUrl.isBlank() ? 0.55f : 1f);
        field.addView(paste, new LinearLayout.LayoutParams(dp(42), dp(42)));
        Motion.press(paste);
        paste.setOnClickListener(v -> {
            String value = readClipboardSmartStoreUrl();
            if (value.isBlank()) {
                toast("클립보드에 SmartStore 상품 링크가 없어요.");
                return;
            }
            input.setText(value);
            input.setSelection(value.length());
            Motion.selection(paste);
        });

        TextView load = text("상품 불러오기", 15, Color.WHITE, Typeface.BOLD);
        load.setGravity(Gravity.CENTER);
        load.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams loadLp = matchWrap();
        loadLp.topMargin = dp(18);
        panel.addView(load, loadLp);
        updateLoadButton(load, input.getText().toString());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateLoadButton(load, s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        Motion.press(load);
        load.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!isSmartStoreProductUrl(url)) return;
            Motion.dismissDialog(dialog, panel, () -> loadProductForEdit(url, null));
        });

        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> addDialog = null);
        dialog.show();
        sizeDialog(dialog, 0.92f);
        Motion.dialogIn(panel);
    }

    private String readClipboardSmartStoreUrl() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return "";
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String value = text == null ? "" : text.toString().trim();
            return isSmartStoreProductUrl(value) ? value : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void updateLoadButton(TextView button, String value) {
        boolean valid = isSmartStoreProductUrl(value == null ? "" : value.trim());
        button.setBackground(roundRect(valid ? BLUE : Color.rgb(190, 198, 207), 14));
        button.setAlpha(valid ? 1f : 0.7f);
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
        if (!isSmartStoreProductUrl(url)) {
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
        boolean[] selectedOnly = {false};
        Set<String> collapsedGroups = new HashSet<>();

        LinearLayout panel = surface(24, 20);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, matchWrap());
        header.addView(text("옵션 선택", 21, TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView close = text("닫기", 14, SUB, Typeface.BOLD);
        close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> Motion.dismissDialog(dialog, panel));
        Motion.press(close);
        header.addView(close);

        TextView productName = text(titleSnapshot, 13, SUB, Typeface.NORMAL);
        productName.setPadding(0, dp(5), 0, dp(12));
        panel.addView(productName);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(TEXT);
        search.setHintTextColor(SUB);
        search.setHint("옵션 검색");
        search.setBackground(roundRect(FIELD, 14));
        search.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(search, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ));

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, dp(10), 0, dp(8));
        panel.addView(filterRow, matchWrap());

        TextView allChip = text("전체", 13, BLUE, Typeface.BOLD);
        allChip.setGravity(Gravity.CENTER);
        allChip.setPadding(dp(13), dp(8), dp(13), dp(8));
        filterRow.addView(allChip);
        TextView selectedChip = text("선택만", 13, SUB, Typeface.BOLD);
        selectedChip.setGravity(Gravity.CENTER);
        selectedChip.setPadding(dp(13), dp(8), dp(13), dp(8));
        LinearLayout.LayoutParams selectedLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        selectedLp.leftMargin = dp(7);
        filterRow.addView(selectedChip, selectedLp);
        TextView selectedCount = text("", 13, BLUE, Typeface.BOLD);
        selectedCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        filterRow.addView(selectedCount, new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout optionList = new LinearLayout(this);
        optionList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(optionList);
        panel.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(390)
        ));

        Motion.press(allChip);
        Motion.press(selectedChip);
        allChip.setOnClickListener(v -> {
            selectedOnly[0] = false;
            renderOptionList(optionList, optionsSnapshot, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);
        });
        selectedChip.setOnClickListener(v -> {
            selectedOnly[0] = true;
            renderOptionList(optionList, optionsSnapshot, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderOptionList(optionList, optionsSnapshot, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        renderOptionList(optionList, optionsSnapshot, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);

        TextView save = text("저장", 15, Color.WHITE, Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setBackground(roundRect(BLUE, 14));
        save.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams saveLp = matchWrap();
        saveLp.topMargin = dp(10);
        panel.addView(save, saveLp);
        Motion.press(save);
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
                Motion.dismissDialog(dialog, panel, () -> renderProducts(true));
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
        sizeDialog(dialog, 0.94f);
        Motion.dialogIn(panel);
    }

    private void renderOptionList(
        LinearLayout container,
        JSONArray options,
        boolean[] checked,
        EditText search,
        boolean[] selectedOnly,
        Set<String> collapsedGroups,
        TextView selectedCount,
        TextView allChip,
        TextView selectedChip
    ) {
        container.removeAllViews();
        updateSelectedCount(selectedCount, checked);
        allChip.setTextColor(selectedOnly[0] ? SUB : BLUE);
        selectedChip.setTextColor(selectedOnly[0] ? BLUE : SUB);
        allChip.setBackground(roundRect(selectedOnly[0] ? FIELD : BLUE_SOFT, 12));
        selectedChip.setBackground(roundRect(selectedOnly[0] ? BLUE_SOFT : FIELD, 12));

        String query = search.getText() == null
            ? ""
            : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean grouped = hasSubOptions(options);
        Map<String, List<Integer>> groups = new LinkedHashMap<>();

        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            if (selectedOnly[0] && !checked[i]) continue;
            String label = optionLabel(option);
            if (!query.isBlank() && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
            String group = grouped ? option.optString("optionName1", "옵션") : "옵션";
            if (group.isBlank() || "null".equals(group)) group = "옵션";
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(i);
        }

        if (groups.isEmpty()) {
            TextView empty = text(selectedOnly[0] ? "선택한 옵션이 없어요" : "검색 결과가 없어요", 13, SUB, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, dp(36));
            container.addView(empty, matchWrap());
            return;
        }

        for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
            String group = entry.getKey();
            List<Integer> indexes = entry.getValue();
            boolean collapsed = query.isBlank() && collapsedGroups.contains(group);

            if (grouped) {
                LinearLayout groupRow = new LinearLayout(this);
                groupRow.setGravity(Gravity.CENTER_VERTICAL);
                groupRow.setPadding(dp(2), dp(10), dp(2), dp(9));
                TextView groupTitle = text(group + "  " + indexes.size(), 14, TEXT, Typeface.BOLD);
                groupRow.addView(groupTitle, new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ));
                TextView arrow = text(collapsed ? "›" : "⌄", 20, SUB, Typeface.NORMAL);
                groupRow.addView(arrow);
                Motion.press(groupRow);
                groupRow.setOnClickListener(v -> {
                    if (collapsedGroups.contains(group)) collapsedGroups.remove(group);
                    else collapsedGroups.add(group);
                    renderOptionList(container, options, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);
                });
                container.addView(groupRow, matchWrap());
            }

            if (collapsed) continue;
            for (int index : indexes) {
                JSONObject option = options.optJSONObject(index);
                int stock = option == null || option.isNull("stockQuantity")
                    ? -1
                    : option.optInt("stockQuantity", -1);

                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(11), dp(14), dp(11));
                row.setBackground(roundRect(checked[index] ? BLUE_SOFT : FIELD, 14));
                LinearLayout.LayoutParams rowLp = matchWrap();
                rowLp.bottomMargin = dp(7);
                container.addView(row, rowLp);

                LinearLayout left = new LinearLayout(this);
                left.setOrientation(LinearLayout.VERTICAL);
                row.addView(left, new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ));
                String displayLabel = grouped ? optionSubLabel(option) : optionLabel(option);
                left.addView(text(displayLabel, 14, TEXT, Typeface.BOLD));
                String stockLabel = stock > 0 ? "재고 " + stock + "개" : stock == 0 ? "품절" : "재고 확인 중";
                TextView stockView = text(stockLabel, 12, stock > 0 ? GREEN : SUB, Typeface.NORMAL);
                stockView.setPadding(0, dp(3), 0, 0);
                left.addView(stockView);

                TextView mark = text(checked[index] ? "✓" : "", 19, BLUE, Typeface.BOLD);
                mark.setGravity(Gravity.CENTER);
                mark.setPadding(dp(12), 0, 0, 0);
                row.addView(mark);
                Motion.press(row);
                row.setOnClickListener(v -> {
                    checked[index] = !checked[index];
                    renderOptionList(container, options, checked, search, selectedOnly, collapsedGroups, selectedCount, allChip, selectedChip);
                });
            }
        }
    }

    private boolean hasSubOptions(JSONArray options) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            String second = option.optString("optionName2", "");
            String third = option.optString("optionName3", "");
            if ((!second.isBlank() && !"null".equals(second)) || (!third.isBlank() && !"null".equals(third))) return true;
        }
        return false;
    }

    private String optionSubLabel(JSONObject option) {
        if (option == null) return "옵션";
        StringBuilder label = new StringBuilder();
        for (String key : new String[]{"optionName2", "optionName3"}) {
            String value = option.optString(key, "");
            if (value.isBlank() || "null".equals(value)) continue;
            if (label.length() > 0) label.append(" / ");
            label.append(value);
        }
        return label.length() == 0 ? optionLabel(option) : label.toString();
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
        renderProducts(false);
    }

    private void renderProducts(boolean animate) {
        if (productList == null) return;
        int oldScroll = productScroll == null ? 0 : productScroll.getScrollY();
        productList.removeAllViews();
        JSONArray products = orderedProducts(ProductStore.list(this));
        if (products.length() == 0) {
            LinearLayout empty = surface(20, 18);
            TextView value = text("등록된 상품이 없어요", 15, SUB, Typeface.NORMAL);
            value.setGravity(Gravity.CENTER);
            empty.addView(value);
            productList.addView(empty, sectionParams());
            rebuildCardDots(0);
            if (animate) Motion.enter(empty, 20L);
            return;
        }

        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null) continue;
            boolean enabled = product.optBoolean("enabled", false);

            LinearLayout card = surface(20, 16);
            productList.addView(card, sectionParams());
            if (animate) Motion.enter(card, 35L + Math.min(i, 6) * 34L);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.TOP);
            card.addView(titleRow, matchWrap());

            TextView name = text(product.optString("title", "SmartStore 상품"), 16, TEXT, Typeface.BOLD);
            titleRow.addView(name, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ));

            TextView badge = text(enabled ? "알림 켜짐" : "알림 꺼짐", 12, enabled ? GREEN : SUB, Typeface.BOLD);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(9), dp(5), dp(9), dp(5));
            badge.setBackground(roundRect(enabled ? GREEN_SOFT : FIELD, 12));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.leftMargin = dp(8);
            titleRow.addView(badge, badgeParams);

            TextView delete = text("삭제", 12, RED, Typeface.BOLD);
            delete.setPadding(dp(9), dp(5), 0, dp(5));
            delete.setOnClickListener(v -> confirmDelete(product));
            Motion.press(delete);
            titleRow.addView(delete);

            Map<String, String> labels = labelMap(product.optJSONObject("selectedLabels"));
            String optionSummary;
            if (labels.isEmpty()) optionSummary = "선택 옵션 없음";
            else if (labels.size() == 1) optionSummary = labels.values().iterator().next();
            else optionSummary = labels.values().iterator().next() + " 외 " + (labels.size() - 1) + "개";
            TextView options = text(optionSummary, 13, SUB, Typeface.NORMAL);
            options.setPadding(0, dp(6), 0, 0);
            card.addView(options);

            String last = product.optString("lastStatus", "");
            if (enabled && !last.isBlank()) {
                TextView state = text(last, 12, SUB, Typeface.NORMAL);
                state.setPadding(0, dp(5), 0, 0);
                card.addView(state);
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(0, dp(11), 0, 0);
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

        rebuildCardDots(productList.getChildCount());

        if (productScroll != null) {
            productScroll.post(() -> {
                productScroll.scrollTo(0, oldScroll);
                updateCardDots();
            });
        }
    }

    private void rebuildCardDots(int productCount) {
        renderedProductCount = Math.max(0, productCount);
        dotWindowStart = -1;
        if (scrollDots == null) return;
        scrollDots.removeAllViews();
        updateCardDots();
    }

    private void updateCardDots() {
        if (productScroll == null || productList == null || scrollDots == null) return;

        int viewport = productScroll.getHeight() - productScroll.getPaddingTop() - productScroll.getPaddingBottom();
        int content = productList.getHeight();
        int count = Math.min(renderedProductCount, productList.getChildCount());
        if (count <= 1 || viewport <= 0 || content <= viewport) {
            scrollDots.setVisibility(View.GONE);
            return;
        }

        int active = closestCardIndex(count);
        int visibleCount = Math.min(7, count);
        int start = count <= 7
            ? 0
            : Math.max(0, Math.min(count - visibleCount, active - visibleCount / 2));

        if (start != dotWindowStart || scrollDots.getChildCount() != visibleCount) {
            buildDotWindow(start, visibleCount);
        }

        scrollDots.setVisibility(View.VISIBLE);
        float viewportCenter = productScroll.getScrollY() + viewport / 2f;

        for (int i = 0; i < scrollDots.getChildCount(); i++) {
            View holder = scrollDots.getChildAt(i);
            Object tag = holder.getTag();
            if (!(tag instanceof Integer)) continue;
            int target = (Integer) tag;
            if (target < 0 || target >= count) continue;

            View dot = holder instanceof FrameLayout && ((FrameLayout) holder).getChildCount() > 0
                ? ((FrameLayout) holder).getChildAt(0)
                : null;
            View card = productList.getChildAt(target);
            if (dot == null || card == null) continue;

            float cardCenter = card.getTop() + card.getHeight() / 2f;
            float distance = Math.abs(cardCenter - viewportCenter);
            float emphasisRange = Math.max(dp(90), card.getHeight() * 0.9f);
            float emphasis = Math.max(0f, 1f - distance / emphasisRange);

            boolean hasBefore = start > 0 && i == 0;
            boolean hasAfter = start + visibleCount < count && i == visibleCount - 1;
            float edgeFactor = (hasBefore || hasAfter) ? 0.78f : 1f;

            float scale = (0.72f + 0.28f * emphasis) * edgeFactor;
            dot.setScaleX(scale);
            dot.setScaleY(scale);
            dot.setAlpha((0.42f + 0.58f * emphasis) * edgeFactor);
            dot.setBackground(roundRect(
                blendColor(Color.rgb(188, 195, 204), BLUE, emphasis),
                8
            ));
        }
    }

    private void buildDotWindow(int start, int visibleCount) {
        if (scrollDots == null) return;
        dotWindowStart = start;
        scrollDots.removeAllViews();

        for (int i = 0; i < visibleCount; i++) {
            int target = start + i;

            FrameLayout holder = new FrameLayout(this);
            holder.setTag(target);
            holder.setContentDescription((target + 1) + "번째 상품");
            LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            scrollDots.addView(holder, holderLp);

            View dot = new View(this);
            dot.setBackground(roundRect(Color.rgb(188, 195, 204), 8));
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                dp(7),
                dp(7),
                Gravity.CENTER
            );
            holder.addView(dot, dotLp);

            holder.setOnClickListener(v -> {
                Object value = v.getTag();
                if (value instanceof Integer) scrollCardToCenter((Integer) value);
            });
        }
    }

    private int closestCardIndex(int count) {
        int viewport = productScroll.getHeight() - productScroll.getPaddingTop() - productScroll.getPaddingBottom();
        float center = productScroll.getScrollY() + viewport / 2f;
        int closest = 0;
        float closestDistance = Float.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            View card = productList.getChildAt(i);
            if (card == null) continue;
            float cardCenter = card.getTop() + card.getHeight() / 2f;
            float distance = Math.abs(cardCenter - center);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = i;
            }
        }
        return closest;
    }

    private void scrollCardToCenter(int index) {
        if (productScroll == null || productList == null
            || index < 0 || index >= productList.getChildCount()) return;

        View card = productList.getChildAt(index);
        if (card == null) return;
        int viewport = productScroll.getHeight() - productScroll.getPaddingTop() - productScroll.getPaddingBottom();
        int target = card.getTop() - Math.max(0, (viewport - card.getHeight()) / 2);
        int maxScroll = Math.max(0, productList.getHeight() - viewport);
        productScroll.smoothScrollTo(0, Math.max(0, Math.min(maxScroll, target)));
    }

    private int blendColor(int from, int to, float amount) {
        float value = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value);
        return Color.rgb(r, g, b);
    }

    private JSONArray orderedProducts(JSONArray source) {
        JSONArray ordered = new JSONArray();
        for (int pass = 0; pass < 2; pass++) {
            boolean wanted = pass == 0;
            for (int i = 0; i < source.length(); i++) {
                JSONObject product = source.optJSONObject(i);
                if (product != null && product.optBoolean("enabled", false) == wanted) ordered.put(product);
            }
        }
        return ordered;
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
        renderProducts(true);
        if (productScroll != null) productScroll.post(() -> productScroll.smoothScrollTo(0, 0));
    }

    private void syncMonitorService() {
        int enabled = ProductStore.enabledCount(this);
        if (enabled <= 0) {
            startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
            MonitorPrefs.setRunning(this, false);
            return;
        }
        startForegroundService(new Intent(this, MonitorService.class));
    }

    private void confirmDelete(JSONObject product) {
        new AlertDialog.Builder(this)
            .setTitle("삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제", (d, w) -> {
                ProductStore.remove(this, product.optString("id"));
                syncMonitorService();
                renderProducts(true);
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
        Motion.pushForResult(this, intent, REQUEST_LOGIN);
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
            renderProducts(true);
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

    private boolean isSmartStoreProductUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        return url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private boolean isProductUrl(String url) {
        return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private boolean isLoginUrl(String url) {
        return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login"));
    }

    private void sizeDialog(Dialog dialog, float widthRatio) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * widthRatio);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.32f;
        window.setAttributes(lp);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
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
        Motion.press(button);
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

    private void resumeMonitorIfNeeded() {
        if (ProductStore.enabledCount(this) > 0
            && hasNaverSession()
            && NotificationAccess.isAllowed(this)) {
            // Defer this work until the screen transition has settled so
            // foreground-service startup and card rebuilding do not fight
            // the animation frames.
            startForegroundService(new Intent(this, MonitorService.class));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusRefresh);
        handler.removeCallbacks(resumeRefresh);

        refreshProductsOnResume = !firstResume;
        long delay = firstResume ? 320L : 300L;
        firstResume = false;
        handler.postDelayed(resumeRefresh, delay);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(resumeRefresh);
        handler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (optionDialog != null && optionDialog.isShowing()) optionDialog.dismiss();
        if (addDialog != null && addDialog.isShowing()) addDialog.dismiss();
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
