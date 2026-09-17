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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private JSONArray products = new JSONArray();
    private JSONObject currentProduct;
    private int currentIndex;
    private int successCount;
    private int failureCount;
    private boolean awaitingResult;
    private boolean stopping;

    private final Runnable resultTimeout = () -> {
        if (!awaitingResult || stopping) return;
        awaitingResult = false;
        markCurrentFailure("조회 시간 초과");
        advanceProduct();
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        stopping = false;
        products = ProductStore.list(this);
        if (products.length() == 0) {
            MonitorPrefs.updateStatus(this, "감시 상품 없음");
            stopSelf();
            return START_NOT_STICKY;
        }
        MonitorPrefs.setRunning(this, true);
        acquireWakeLock();
        startForeground(NOTIFICATION_MONITOR, buildOngoingNotification("감시 준비 중"));
        ensureWebView();
        startCycle();
        return START_STICKY;
    }

    private void startCycle() {
        if (stopping) return;
        products = ProductStore.list(this);
        if (products.length() == 0) { stopSelf(); return; }
        currentIndex = 0;
        successCount = 0;
        failureCount = 0;
        loadCurrentProduct();
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
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv)", ")").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new RestockBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                awaitingResult = false;
                handler.removeCallbacks(resultTimeout);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (stopping) return;
                if (isLoginUrl(url)) {
                    MonitorPrefs.updateStatus(MonitorService.this, "네이버 로그인 필요");
                    updateOngoingNotification("로그인 필요 · 앱을 열어 로그인해주세요");
                    stopSelf();
                    return;
                }
                if (isProductUrl(url)) handler.postDelayed(MonitorService.this::runInventoryCheck, 800L);
                else {
                    markCurrentFailure("상품 페이지 연결 실패");
                    advanceProduct();
                }
            }
        });
    }

    private void loadCurrentProduct() {
        if (stopping) return;
        while (currentIndex < products.length()) {
            currentProduct = products.optJSONObject(currentIndex);
            if (currentProduct != null && ProductStore.selectedCount(currentProduct) > 0) break;
            currentIndex++;
        }
        if (currentIndex >= products.length()) {
            finishCycle();
            return;
        }
        String title = currentProduct.optString("title", "상품");
        updateOngoingNotification((currentIndex + 1) + "/" + products.length() + " · " + title);
        awaitingResult = false;
        handler.removeCallbacks(resultTimeout);
        webView.loadUrl(currentProduct.optString("url"));
    }

    private void runInventoryCheck() {
        if (stopping || awaitingResult || currentProduct == null) return;
        if (!isProductUrl(webView.getUrl())) {
            markCurrentFailure("상품 페이지 연결 실패");
            advanceProduct();
            return;
        }
        awaitingResult = true;
        handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS);
        webView.evaluateJavascript(InventoryScript.SCRIPT, ignored -> {});
    }

    private class RestockBridge {
        @JavascriptInterface public void onResult(String json) { handler.post(() -> handleInventoryResult(json)); }
    }

    private void handleInventoryResult(String json) {
        if (stopping || currentProduct == null) return;
        awaitingResult = false;
        handler.removeCallbacks(resultTimeout);
        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok", false)) {
                String error = result.optString("error", "조회 실패");
                if (result.has("status") && !result.isNull("status")) error += " · HTTP " + result.optInt("status");
                markCurrentFailure(error);
            } else {
                processSuccessfulSnapshot(result);
                successCount++;
            }
        } catch (Exception e) {
            markCurrentFailure("결과 처리 실패");
        }
        advanceProduct();
    }

    private void processSuccessfulSnapshot(JSONObject result) throws Exception {
        JSONArray selectedIds = currentProduct.optJSONArray("selectedIds");
        JSONObject labels = currentProduct.optJSONObject("selectedLabels");
        if (selectedIds == null) selectedIds = new JSONArray();
        if (labels == null) labels = new JSONObject();
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < selectedIds.length(); i++) selected.add(selectedIds.optString(i));

        JSONArray options = result.optJSONArray("options");
        if (options == null) options = new JSONArray();
        Map<String, Boolean> current = new HashMap<>();
        Map<String, String> currentLabels = new HashMap<>();
        int available = 0;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            String id = option.optString("id");
            if (!selected.contains(id)) continue;
            boolean inStock = option.optBoolean("available", false);
            current.put(id, inStock);
            currentLabels.put(id, MainActivity.optionLabel(option));
            if (inStock) available++;
        }

        JSONObject previous = currentProduct.optJSONObject("lastAvailability");
        if (previous == null) previous = new JSONObject();
        JSONArray restocked = new JSONArray();
        for (String id : selected) {
            if (!current.containsKey(id)) continue;
            boolean now = current.get(id);
            if (previous.has(id) && !previous.optBoolean(id, false) && now) {
                restocked.put(currentLabels.getOrDefault(id, labels.optString(id, id)));
            }
            previous.put(id, now);
        }

        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        currentProduct.put("lastAvailability", previous);
        currentProduct.put("lastStatus", "정상 · 재고 " + available + "/" + selected.size() + " · " + time);
        currentProduct.put("lastCheck", System.currentTimeMillis());
        products.put(currentIndex, currentProduct);
        ProductStore.save(this, products);

        if (restocked.length() > 0) notifyRestock(currentProduct.optString("title", "재입고"), currentProduct.optString("url"), restocked);
    }

    private void markCurrentFailure(String reason) {
        failureCount++;
        try {
            if (currentProduct != null) {
                currentProduct.put("lastStatus", "조회 실패 · " + reason);
                currentProduct.put("lastCheck", System.currentTimeMillis());
                products.put(currentIndex, currentProduct);
                ProductStore.save(this, products);
            }
        } catch (Exception ignored) {}
    }

    private void advanceProduct() {
        if (stopping) return;
        currentIndex++;
        handler.postDelayed(this::loadCurrentProduct, 500L);
    }

    private void finishCycle() {
        if (stopping) return;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        String status = failureCount == 0
            ? "정상 · " + successCount + "개 상품 · " + time
            : "완료 · 성공 " + successCount + " · 실패 " + failureCount + " · " + time;
        MonitorPrefs.updateStatus(this, status);
        updateOngoingNotification(status);
        handler.postDelayed(this::startCycle, MonitorPrefs.intervalSeconds(this) * 1000L);
    }

    private boolean isProductUrl(String url) { return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/"); }
    private boolean isLoginUrl(String url) { return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login")); }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keyboard-alert:restock-monitor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "재입고 감시 상태", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("재입고 감시 실행 상태");
        manager.createNotificationChannel(monitor);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "재입고 알림", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("선택한 옵션이 재입고되면 알려줍니다.");
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildOngoingNotification(String text) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Keyboard Restock · 감시 중")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .addAction(new Notification.Action.Builder(null, "감시 중지", stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void updateOngoingNotification(String text) { getSystemService(NotificationManager.class).notify(NOTIFICATION_MONITOR, buildOngoingNotification(text)); }

    private void notifyRestock(String title, String url, JSONArray labels) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < labels.length(); i++) { if (i > 0) body.append(" · "); body.append(labels.optString(i)); }
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openProduct = PendingIntent.getActivity(this, Math.abs(url.hashCode()), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("📦 재입고 · " + title)
            .setContentText(body.toString())
            .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
            .setContentIntent(openProduct)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(Notification.CATEGORY_ALARM)
            .build();
        getSystemService(NotificationManager.class).notify(42000 + Math.abs((title + body).hashCode() % 1000), notification);
    }

    @Override public void onDestroy() {
        stopping = true;
        awaitingResult = false;
        handler.removeCallbacksAndMessages(null);
        MonitorPrefs.setRunning(this, false);
        if (webView != null) { webView.removeJavascriptInterface("RestockBridge"); webView.stopLoading(); webView.destroy(); webView = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
