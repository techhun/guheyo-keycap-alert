package com.techhun.keyboardalert.restock;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

final class BatteryAccess {
    private BatteryAccess() {}

    static boolean isBackgroundRestricted(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        ActivityManager manager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.isBackgroundRestricted();
    }

    static Intent settingsIntent(Context context) {
        Intent intent = new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.getPackageName())
        );
        if (intent.resolveActivity(context.getPackageManager()) != null) return intent;
        return new Intent(Settings.ACTION_SETTINGS);
    }
}
