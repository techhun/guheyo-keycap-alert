package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private static final int BG = Color.rgb(247, 248, 250);
    private static final int WHITE = Color.WHITE;
    private static final int TEXT = Color.rgb(25, 31, 40);
    private static final int SUB = Color.rgb(139, 149, 161);
    private static final int BLUE = Color.rgb(49, 130, 246);
    private static final int BLUE_SOFT = Color.rgb(235, 244, 255);
    private static final int FIELD = Color.rgb(242, 244, 246);
    private static final int[] VALUES = {15, 30, 60};
    private static final String[] LABELS = {"15초", "30초", "60초"};

    private TextView[] chips;
    private int interval;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        interval = MonitorPrefs.intervalSeconds(this);

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
            view.setPadding(dp(20), top + dp(10), dp(20), bottom + dp(18));
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        TextView back = text("‹", 34, TEXT, Typeface.NORMAL);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(46)));

        header.addView(text("설정", 24, TEXT, Typeface.BOLD));

        LinearLayout card = surface(20, 18);
        LinearLayout.LayoutParams cardLp = matchWrap();
        cardLp.topMargin = dp(22);
        root.addView(card, cardLp);
        card.addView(text("조회 주기", 15, TEXT, Typeface.BOLD));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(12), 0, 0);
        card.addView(row, matchWrap());
        chips = new TextView[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) {
            final int value = VALUES[i];
            TextView chip = text(LABELS[i], 14, SUB, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                interval = value;
                MonitorPrefs.prefs(this).edit().putInt(MonitorPrefs.KEY_INTERVAL, value).apply();
                refreshChips();
            });
            chips[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        refreshChips();

        TextView note = text("알림이 켜진 상품들은 이 주기 안에서 나눠서 확인해요.", 12, SUB, Typeface.NORMAL);
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note);

        TextView version = text("버전  " + versionName(), 13, SUB, Typeface.NORMAL);
        LinearLayout.LayoutParams versionLp = matchWrap();
        versionLp.topMargin = dp(22);
        root.addView(version, versionLp);

        setContentView(root);
        root.requestApplyInsets();
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void refreshChips() {
        if (chips == null) return;
        for (int i = 0; i < chips.length; i++) {
            boolean selected = VALUES[i] == interval;
            chips[i].setTextColor(selected ? BLUE : SUB);
            chips[i].setBackground(roundRect(selected ? BLUE_SOFT : FIELD, 12));
        }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
