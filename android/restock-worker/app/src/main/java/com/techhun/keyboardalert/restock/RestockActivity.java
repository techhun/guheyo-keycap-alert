package com.techhun.keyboardalert.restock;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
        decorateBrand();
    }

    private void decorateBrand() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup contentGroup) || contentGroup.getChildCount() == 0) return;
        View rootView = contentGroup.getChildAt(0);
        if (!(rootView instanceof LinearLayout root) || root.getChildCount() == 0) return;
        View headerView = root.getChildAt(0);
        if (!(headerView instanceof LinearLayout header) || header.getChildCount() < 4) return;

        View iconView = header.getChildAt(0);
        if (iconView instanceof ImageView icon) {
            icon.setImageResource(R.drawable.restock_icon);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        }

        if (header.getChildCount() > 1
            && header.getChildAt(1) instanceof TextView existing
            && "Restock".contentEquals(existing.getText())) return;

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
