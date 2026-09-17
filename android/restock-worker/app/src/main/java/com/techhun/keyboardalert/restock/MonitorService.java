package com.techhun.keyboardalert.restock;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
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
import java.util.List;
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
    private boolean awaitingResult;
    private boolean stopping;

    private final Runnable resultTimeout = () -> {
        if (!awaitingResult || stopping) return;
        awaitingResult = false;
        setStatus("조회 시간 초과");
        updateOngoingNotification("조회 시간 초과 · 다음 주기에 재시도");
        scheduleNext(true);
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
        String url = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_URL, "");
        List<String> ids = MonitorPrefs.selectedIds(this);
        if (url.isBlank() || ids.isEmpty()) {
            MonitorPrefs.updateStatus(this, "감시 설정 없음");
            stopSelf();
            return START_NOT_STICKY;
        }

        MonitorPrefs.setRunning(this, true);
        acquireWakeLock();
        startForeground(NOTIFICATION_MONITOR, buildOngoingNotification("감시 준비 중"));
        ensureWebView();
        loadConfiguredProduct();
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
        String browserUserAgent = settings.getUserAgentString()
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "");
        settings.setUserAgentString(browserUserAgent);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new RestockBridge(), "RestockBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                awaitingResult = false;
                handler.removeCallbacks(resultTimeout);
                if (isLoginUrl(url)) {
                    setStatus("네이버 로그인 필요");
                    updateOngoingNotification("로그인 필요 · 앱을 열어 로그인하세요");
                } else {
                    updateOngoingNotification("상품 페이지 로딩 중");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (stopping) return;
                if (isProductUrl(url)) {
                    handler.postDelayed(MonitorService.this::runInventoryCheck, 1200L);
                } else if (isLoginUrl(url)) {
                    setStatus("네이버 로그인 필요");
                    updateOngoingNotification("로그인 필요 · 앱에서 로그인 후 다시 시작");
                    scheduleNext(true);
                }
            }
        });
    }

    private void loadConfiguredProduct() {
        if (stopping || webView == null) return;
        String url = MonitorPrefs.prefs(this).getString(MonitorPrefs.KEY_URL, "");
        if (url.isBlank()) {
            stopSelf();
            return;
        }
        awaitingResult = false;
        handler.removeCallbacks(resultTimeout);
        webView.loadUrl(url);
    }

    private boolean isProductUrl(String url) {
        return url != null && url.contains("smartstore.naver.com/") && url.contains("/products/");
    }

    private boolean isLoginUrl(String url) {
        return url != null && (url.contains("nid.naver.com") || url.contains("nidlogin.login"));
    }

    private void runInventoryCheck() {
        if (stopping || webView == null || awaitingResult) return;
        String currentUrl = webView.getUrl();
        if (!isProductUrl(currentUrl)) {
            loadConfiguredProduct();
            return;
        }

        awaitingResult = true;
        updateOngoingNotification("재고 조회 중");
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

        try {
            JSONObject result = new JSONObject(json);
            if (!result.optBoolean("ok", false)) {
                String error = result.optString("error", "UNKNOWN");
                String detail = result.has("status") && !result.isNull("status")
                    ? error + " (HTTP " + result.optInt("status") + ")"
                    : error;
                setStatus("조회 실패 · " + detail);
                updateOngoingNotification("조회 실패 · " + detail);
                scheduleNext("PRODUCT_NO_NOT_FOUND".equals(error) || "CHANNEL_UID_NOT_FOUND".equals(error));
                return;
            }

            processSuccessfulSnapshot(result);
            scheduleNext(false);
        } catch (Exception error) {
            setStatus("결과 처리 실패 · " + error.getClass().getSimpleName());
            updateOngoingNotification("결과 처리 실패");
            scheduleNext(true);
        }
    }

    private void processSuccessfulSnapshot(JSONObject result) throws Exception {
        SharedPreferences prefs = MonitorPrefs.prefs(this);
        Set<String> selected = new HashSet<>(MonitorPrefs.selectedIds(this));
        Map<String, String> configuredLabels = MonitorPrefs.selectedLabels(this);
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
            if (available) availableCount += 1;
        }

        JSONObject previous;
        try {
            previous = new JSONObject(prefs.getString(MonitorPrefs.KEY_LAST_AVAILABILITY, "{}"));
        } catch (Exception ignored) {
            previous = new JSONObject();
        }

        JSONArray restocked = new JSONArray();
        for (String id : selected) {
            boolean now = current.getOrDefault(id, false);
            if (previous.has(id) && !previous.optBoolean(id, false) && now) {
                restocked.put(currentLabels.getOrDefault(id, configuredLabels.getOrDefault(id, id)));
            }
            previous.put(id, now);
        }

        String time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(new Date());
        String status = "정상 · 재고 " + availableCount + "/" + selected.size() + " · " + time;
        prefs.edit()
            .putString(MonitorPrefs.KEY_LAST_AVAILABILITY, previous.toString())
            .putString(MonitorPrefs.KEY_LAST_STATUS, status)
            .putLong(MonitorPrefs.KEY_LAST_CHECK, System.currentTimeMillis())
            .apply();
        updateOngoingNotification(status);

        if (restocked.length() > 0) {
            notifyRestock(result.optString("title", prefs.getString(MonitorPrefs.KEY_TITLE, "재입고")), restocked);
        }
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

    private void scheduleNext(boolean reloadPage) {
        if (stopping) return;
        int seconds = MonitorPrefs.intervalSeconds(this);
        handler.postDelayed(() -> {
            if (stopping) return;
            if (reloadPage) loadConfiguredProduct();
            else runInventoryCheck();
        }, seconds * 1000L);
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
        NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "재입고 감시 상태", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("재입고 감시가 실행 중일 때 표시됩니다.");
        manager.createNotificationChannel(monitor);

        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "재입고 알림", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("선택한 옵션이 재입고되면 알려줍니다.");
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildOngoingNotification(String text) {
        Intent stopIntent = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Keyboard Restock · 감시 중")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .addAction(new Notification.Action.Builder(null, "감시 중지", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void updateOngoingNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_MONITOR, buildOngoingNotification(text));
    }

    private void notifyRestock(String title, JSONArray labels) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < labels.length(); i++) {
            if (i > 0) text.append(" · ");
            text.append(labels.optString(i));
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("📦 재입고 · " + title)
            .setContentText(text.toString())
            .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(Notification.CATEGORY_ALARM)
            .build();
        getSystemService(NotificationManager.class).notify(42000 + Math.abs(text.toString().hashCode() % 1000), notification);
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
