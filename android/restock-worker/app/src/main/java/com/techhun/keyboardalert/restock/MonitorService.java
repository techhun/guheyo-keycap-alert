package com.techhun.keyboardalert.restock;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MonitorService extends Service {
    static final String ACTION_STOP = "com.techhun.keyboardalert.restock.STOP";

    private static final String CHANNEL_MONITOR = "restock_monitor";
    private static final String CHANNEL_ALERT = "restock_alert";
    private static final int NOTIFICATION_MONITOR = 41001;
    private static final long RESULT_TIMEOUT_MS = 20_000L;
    private static final long MIN_PRODUCT_SPACING_MS = 1_000L;

    private enum Mode { BOOTSTRAP, DIRECT, DISCOVERY }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private JSONArray products = new JSONArray();
    private int currentIndex;
    private boolean awaitingResult;
    private boolean stopping;
    private boolean bootstrapReady;
    private Mode mode = Mode.BOOTSTRAP;
    private JSONObject currentProduct;

    private final Runnable resultTimeout = () -> {
        if (!awaitingResult || stopping) return;
        awaitingResult = false;
        markCurrentFailure("조회 시간 초과");
        scheduleNextProduct();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stopping = false;
        products = ProductStore.enabledList(this);
        if (products.length() == 0) {
            MonitorPrefs.setRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        MonitorPrefs.setRunning(this, true);
        acquireWakeLock();
        startForeground(NOTIFICATION_MONITOR, buildOngoingNotification(products.length() + "개 상품 감시 중"));
        ensureWebView();

        if (!bootstrapReady) {
            bootstrapSession();
        }
        return START_STICKY;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString()
            .replace("; wv)", ")")
            .replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new RestockBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (stopping) return;
                if (isLoginUrl(url)) {
                    setStatus("로그인 필요");
                    updateOngoingNotification("로그인이 필요해요");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (stopping) return;
                if (isLoginUrl(url)) {
                    setStatus("로그인 필요");
                    updateOngoingNotification("앱에서 다시 로그인해주세요");
                    stopSelf();
                    return;
                }
                if (!isProductUrl(url)) return;

                if (mode == Mode.BOOTSTRAP && !bootstrapReady) {
                    bootstrapReady = true;
                    currentIndex = 0;
                    handler.postDelayed(MonitorService.this::checkCurrentProduct, 500L);
                } else if (mode == Mode.DISCOVERY) {
                    handler.postDelayed(MonitorService.this::runDiscoveryCheck, 700L);
                }
            }
        });
    }

    private void bootstrapSession() {
        products = ProductStore.enabledList(this);
        JSONObject first = products.optJSONObject(0);
        if (first == null) {
            stopSelf();
            return;
        }
        mode = Mode.BOOTSTRAP;
        bootstrapReady = false;
        webView.loadUrl(first.optString("url"));
    }

    private void checkCurrentProduct() {
        if (stopping || !bootstrapReady || awaitingResult) return;
        products = ProductStore.enabledList(this);
        if (products.length() == 0) {
            stopSelf();
            return;
        }
        if (currentIndex >= products.length()) currentIndex = 0;
        currentProduct = products.optJSONObject(currentIndex);
        if (currentProduct == null) {
            scheduleNextProduct();
            return;
        }

        String apiUrl = currentProduct.optString("apiUrl", "");
        if (apiUrl.isBlank()) {
            mode = Mode.DISCOVERY;
            webView.loadUrl(currentProduct.optString("url"));
            return;
        }

        mode = Mode.DIRECT;
        awaitingResult = true;
        handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS);
        webView.evaluateJavascript(InventoryApiScript.build(currentProduct), ignored -> {});
    }

    private void runDiscoveryCheck() {
        if (stopping || awaitingResult || currentProduct == null) return;
        JSONObject latest = ProductStore.find(this, currentProduct.optString("id"));
        if (latest == null || !latest.optBoolean("enabled", false)) {
            scheduleNextProduct();
            return;
        }
        currentProduct = latest;
        awaitingResult = true;
        handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS);
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
    }

    private class RestockBridge {
        @JavascriptInterface
        public void onResult(String json) {
            handler.post(() -> handleInventoryResult(json));
        }
    }

    private void handleInventoryResult(String json) {
        if (stopping) return;
        awaitingResult = false;
        handler.removeCallbacks(resultTimeout);
        if (currentProduct == null) {
            scheduleNextProduct();
            return;
        }

        JSONObject latest = ProductStore.find(this, currentProduct.optString("id"));
        if (latest == null || !latest.optBoolean("enabled", false)) {
            scheduleNextProduct();
            return;
        }
        currentProduct = latest;

        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok", false)) {
                String error = result.optString("error", "UNKNOWN");
                int status = result.optInt("status", 0);
                if (status == 401 || status == 403) {
                    setStatus("로그인 필요");
                    updateOngoingNotification("앱에서 다시 로그인해주세요");
                    stopSelf();
                    return;
                }

                // 저장된 빠른 API가 더 이상 유효하지 않으면 이 상품만 다시 탐색한다.
                if (mode == Mode.DIRECT && (
                    "PRODUCT_API_FAILED".equals(error)
                        || "API_URL_MISSING".equals(error)
                        || status == 204
                        || status == 404
                )) {
                    currentProduct.put("apiUrl", "");
                    ProductStore.updateRuntime(this, currentProduct);
                    mode = Mode.DISCOVERY;
                    webView.loadUrl(currentProduct.optString("url"));
                    return;
                }

                String detail = status > 0 ? "조회 실패 · HTTP " + status : "조회 실패";
                markCurrentFailure(detail);
                scheduleNextProduct();
                return;
            }

            if (mode == Mode.DISCOVERY) {
                currentProduct.put("apiUrl", result.optString("apiUrl", ""));
                currentProduct.put("channelUid", result.optString("channelUid", ""));
                currentProduct.put("productNo", result.optString("productNo", ""));
                if (!result.optString("title", "").isBlank()) {
                    currentProduct.put("title", result.optString("title"));
                }
            }

            processSuccessfulSnapshot(currentProduct, result);
            ProductStore.updateRuntime(this, currentProduct);
            scheduleNextProduct();
        } catch (Exception error) {
            markCurrentFailure("결과 처리 실패");
            scheduleNextProduct();
        }
    }

    private void processSuccessfulSnapshot(JSONObject product, JSONObject result) throws Exception {
        JSONArray selectedArray = product.optJSONArray("selectedIds");
        Set<String> selected = new HashSet<>();
        if (selectedArray != null) {
            for (int i = 0; i < selectedArray.length(); i++) selected.add(selectedArray.optString(i));
        }
        JSONObject configuredLabels = product.optJSONObject("selectedLabels");
        if (configuredLabels == null) configuredLabels = new JSONObject();

        JSONArray options = result.optJSONArray("options");
        if (options == null) options = new JSONArray();
        Map<String, Boolean> current = new HashMap<>();
        Map<String, String> currentLabels = new HashMap<>();
        int availableCount = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            String id = option.optString("id", "");
            if (!selected.contains(id)) continue;
            boolean available = option.optBoolean("available", false);
            current.put(id, available);
            currentLabels.put(id, optionLabel(option));
            if (available) availableCount++;
        }

        JSONObject previous = product.optJSONObject("lastAvailability");
        if (previous == null) previous = new JSONObject();
        JSONArray restocked = new JSONArray();
        for (String id : selected) {
            boolean now = current.getOrDefault(id, false);
            if (previous.has(id) && !previous.optBoolean(id, false) && now) {
                restocked.put(currentLabels.getOrDefault(id, configuredLabels.optString(id, id)));
            }
            previous.put(id, now);
        }

        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        product.put("lastAvailability", previous);
        product.put("lastStatus", "재고 " + availableCount + "/" + selected.size() + " · " + time);
        product.put("lastCheck", System.currentTimeMillis());

        int enabledCount = ProductStore.enabledCount(this);
        MonitorPrefs.prefs(this).edit()
            .putString(MonitorPrefs.KEY_LAST_STATUS, enabledCount + "개 감시 중")
            .putLong(MonitorPrefs.KEY_LAST_CHECK, System.currentTimeMillis())
            .apply();
        updateOngoingNotification(enabledCount + "개 상품 감시 중");

        if (restocked.length() > 0) {
            notifyRestock(product.optString("title", "재입고"), product.optString("url", ""), restocked);
        }
    }

    private void markCurrentFailure(String message) {
        if (currentProduct != null) {
            try {
                currentProduct.put("lastStatus", message);
                currentProduct.put("lastCheck", System.currentTimeMillis());
                ProductStore.updateRuntime(this, currentProduct);
            } catch (Exception ignored) {}
        }
        setStatus(message);
    }

    private void scheduleNextProduct() {
        if (stopping) return;
        products = ProductStore.enabledList(this);
        if (products.length() == 0) {
            stopSelf();
            return;
        }
        currentIndex = (currentIndex + 1) % products.length();
        mode = Mode.DIRECT;
        long spacing = Math.max(
            MIN_PRODUCT_SPACING_MS,
            MonitorPrefs.intervalSeconds(this) * 1000L / Math.max(1, products.length())
        );
        handler.postDelayed(this::checkCurrentProduct, spacing);
    }

    private String optionLabel(JSONObject option) {
        StringBuilder label = new StringBuilder();
        for (String key : new String[]{"optionName1", "optionName2", "optionName3"}) {
            String value = option.optString(key, "");
            if (value.isBlank() || "null".equals(value)) continue;
            if (label.length() > 0) label.append(" / ");
            label.append(value);
        }
        return label.length() > 0 ? label.toString() : option.optString("id", "옵션");
    }

    private boolean isProductUrl(String url) {
        return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private boolean isLoginUrl(String url) {
        return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login"));
    }

    private void setStatus(String status) {
        MonitorPrefs.updateStatus(this, status);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keyboard-alert:restock-monitor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(
            CHANNEL_MONITOR,
            "재입고 감시 상태",
            NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(monitor);

        NotificationChannel alert = new NotificationChannel(
            CHANNEL_ALERT,
            "재입고 알림",
            NotificationManager.IMPORTANCE_HIGH
        );
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private Notification buildOngoingNotification(String text) {
        return new Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Keyboard Restock")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void updateOngoingNotification(String text) {
        getSystemService(NotificationManager.class).notify(
            NOTIFICATION_MONITOR,
            buildOngoingNotification(text)
        );
    }

    private void notifyRestock(String title, String productUrl, JSONArray labels) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < labels.length(); i++) {
            if (i > 0) text.append(" · ");
            text.append(labels.optString(i));
        }

        Intent open = productUrl == null || productUrl.isBlank()
            ? new Intent(this, MainActivity.class)
            : new Intent(Intent.ACTION_VIEW, Uri.parse(productUrl));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            Math.abs((title + productUrl).hashCode()),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("📦 재입고 · " + title)
            .setContentText(text.toString())
            .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(Notification.CATEGORY_ALARM)
            .build();
        getSystemService(NotificationManager.class).notify(
            42000 + Math.abs((title + text).hashCode() % 1000),
            notification
        );
    }

    @Override
    public void onDestroy() {
        stopping = true;
        awaitingResult = false;
        handler.removeCallbacksAndMessages(null);
        MonitorPrefs.setRunning(this, false);
        if (webView != null) {
            webView.removeJavascriptInterface("RestockBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
