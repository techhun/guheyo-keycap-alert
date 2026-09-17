package com.techhun.keyboardalert.restock;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class RestockActivity extends MainActivity {
    private static final int TEXT = Color.rgb(25, 31, 40);
    private final Handler sessionHandler = new Handler(Looper.getMainLooper());
    private boolean routingToGate;

    private final Runnable sessionCheck = new Runnable() {
        @Override public void run() {
            if (!SessionState.hasNaverSession()) {
                routeToGate();
                return;
            }
            sessionHandler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SessionState.hasNaverSession()) {
            routeToGate();
            return;
        }
        decorateHeader();
    }

    private void decorateHeader() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup contentGroup) || contentGroup.getChildCount() == 0) return;
        View rootView = contentGroup.getChildAt(0);
        if (!(rootView instanceof LinearLayout root) || root.getChildCount() == 0) return;
        View headerView = root.getChildAt(0);
        if (!(headerView instanceof LinearLayout header) || header.getChildCount() < 4) return;

        View iconView = header.getChildAt(0);
        if (iconView instanceof ImageView icon) {
            icon.setImageResource(R.drawable.restock_icon);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
            icon.setLayoutParams(iconLp);
        }

        TextView brand = new TextView(this);
        brand.setText("Restock");
        brand.setTextSize(22f);
        brand.setTextColor(TEXT);
        brand.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        brandLp.leftMargin = dp(10);
        header.addView(brand, 1, brandLp);

        // Header order after insertion: icon, brand, spacer, logout, settings.
        View sessionView = header.getChildAt(3);
        if (sessionView instanceof TextView logout) {
            logout.setText("로그아웃");
            logout.setOnClickListener(v -> confirmLogout());
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
            .setTitle("로그아웃할까요?")
            .setMessage("켜진 재입고 알림도 함께 꺼져요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("로그아웃", (dialog, which) -> logout())
            .show();
    }

    private void logout() {
        ProductStore.disableAll(this);
        startService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
        MonitorPrefs.setRunning(this, false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(value -> {
            cookies.flush();
            WebStorage.getInstance().deleteAllData();
            runOnUiThread(this::routeToGate);
        });
    }

    private void routeToGate() {
        if (routingToGate || isFinishing()) return;
        routingToGate = true;
        sessionHandler.removeCallbacksAndMessages(null);
        Intent intent = new Intent(this, GateActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!SessionState.hasNaverSession()) {
            routeToGate();
            return;
        }
        sessionHandler.removeCallbacks(sessionCheck);
        sessionHandler.post(sessionCheck);
    }

    @Override
    protected void onPause() {
        sessionHandler.removeCallbacks(sessionCheck);
        if (!SessionState.hasNaverSession() && !isFinishing()) routeToGate();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sessionHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
