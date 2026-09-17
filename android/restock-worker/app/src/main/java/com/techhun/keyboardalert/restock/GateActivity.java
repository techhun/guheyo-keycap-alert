package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class GateActivity extends Activity {
    private static final int REQUEST_LOGIN = 9101;
    private static final int BLUE = Color.rgb(49, 130, 246);
    private static final int TEXT = Color.rgb(25, 31, 40);

    private boolean loginLaunching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        if (SessionState.hasNaverSession()) {
            openMain();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(32), 0, dp(32), 0);
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
            view.setPadding(dp(32), top, dp(32), bottom);
            return insets;
        });

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(group, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.restock_icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        group.addView(icon, new LinearLayout.LayoutParams(dp(116), dp(116)));

        TextView name = new TextView(this);
        name.setText("Restock");
        name.setTextSize(28f);
        name.setTextColor(TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameLp.topMargin = dp(14);
        group.addView(name, nameLp);

        TextView login = new TextView(this);
        login.setText("네이버 로그인");
        login.setTextSize(16f);
        login.setTextColor(Color.WHITE);
        login.setTypeface(null, Typeface.BOLD);
        login.setGravity(Gravity.CENTER);
        login.setBackground(roundRect(BLUE, 16));
        login.setOnClickListener(v -> launchLogin());
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        );
        loginLp.topMargin = dp(34);
        group.addView(login, loginLp);

        setContentView(root);
        root.requestApplyInsets();
    }

    private void launchLogin() {
        if (loginLaunching) return;
        loginLaunching = true;
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra(LoginActivity.EXTRA_TARGET_URL, SessionState.SMARTSTORE_HOME);
        startActivityForResult(intent, REQUEST_LOGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOGIN) return;
        loginLaunching = false;
        if (resultCode == RESULT_OK && SessionState.hasNaverSession()) openMain();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loginLaunching && SessionState.hasNaverSession()) openMain();
    }

    private void openMain() {
        Intent intent = new Intent(this, RestockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
