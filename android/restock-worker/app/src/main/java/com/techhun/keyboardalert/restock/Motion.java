package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

final class Motion {
    private static final PathInterpolator EASE_OUT =
        new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final PathInterpolator EASE =
        new PathInterpolator(0.32f, 0f, 0.2f, 1f);

    private Motion() {}

    static void press(View view) {
        if (view == null) return;
        view.setOnTouchListener((target, event) -> {
            if (!target.isEnabled()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                target.animate()
                    .cancel();
                target.animate()
                    .scaleX(0.972f)
                    .scaleY(0.972f)
                    .setDuration(90L)
                    .setInterpolator(EASE)
                    .start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                target.animate()
                    .cancel();
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(EASE_OUT)
                    .start();
            }
            return false;
        });
    }

    static void enter(View view, long delayMs) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 12f));
        view.setScaleX(0.992f);
        view.setScaleY(0.992f);
        view.post(() -> view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(Math.max(0L, delayMs))
            .setDuration(320L)
            .setInterpolator(EASE_OUT)
            .start());
    }

    static void dialogIn(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 10f));
        view.setScaleX(0.965f);
        view.setScaleY(0.965f);
        view.post(() -> view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(EASE_OUT)
            .start());
    }

    static void selection(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.965f);
        view.setScaleY(0.965f);
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240L)
            .setInterpolator(EASE_OUT)
            .start();
    }

    static void valueChange(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0.65f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(EASE_OUT)
            .start();
    }

    static void push(Activity activity, Intent intent) {
        activity.startActivity(intent);
        activity.overridePendingTransition(
            R.anim.restock_push_in,
            R.anim.restock_push_out
        );
    }

    static void pushForResult(Activity activity, Intent intent, int requestCode) {
        activity.startActivityForResult(intent, requestCode);
        activity.overridePendingTransition(
            R.anim.restock_push_in,
            R.anim.restock_push_out
        );
    }

    static void pop(Activity activity) {
        activity.overridePendingTransition(
            R.anim.restock_pop_in,
            R.anim.restock_pop_out
        );
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
