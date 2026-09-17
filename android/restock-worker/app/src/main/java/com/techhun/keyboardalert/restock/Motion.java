package com.techhun.keyboardalert.restock;

import android.app.Activity;
import android.app.Dialog;
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
                target.animate().cancel();
                target.animate()
                    .scaleX(0.975f)
                    .scaleY(0.975f)
                    .setDuration(85L)
                    .setInterpolator(EASE)
                    .start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                target.animate().cancel();
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190L)
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
        view.setTranslationY(dp(view, 8f));
        view.setScaleX(0.995f);
        view.setScaleY(0.995f);
        view.post(() -> view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(Math.max(0L, delayMs))
            .setDuration(280L)
            .setInterpolator(EASE_OUT)
            .start());
    }

    static void dialogIn(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 7f));
        view.setScaleX(0.975f);
        view.setScaleY(0.975f);
        view.post(() -> view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(EASE_OUT)
            .start());
    }

    static void dismissDialog(Dialog dialog, View view) {
        dismissDialog(dialog, view, null);
    }

    static void dismissDialog(Dialog dialog, View view, Runnable after) {
        if (dialog == null || !dialog.isShowing() || view == null) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            if (after != null) after.run();
            return;
        }
        view.animate().cancel();
        view.animate()
            .alpha(0f)
            .translationY(dp(view, 5f))
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(145L)
            .setInterpolator(EASE)
            .withEndAction(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (after != null) after.run();
            })
            .start();
    }

    static void selection(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.972f);
        view.setScaleY(0.972f);
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200L)
            .setInterpolator(EASE_OUT)
            .start();
    }

    static void valueChange(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(0.72f);
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190L)
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
