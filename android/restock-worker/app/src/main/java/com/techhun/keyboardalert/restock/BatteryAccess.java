package com.techhun.keyboardalert.restock;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

final class BatteryAccess {
    private BatteryAccess() {}

    static boolean isOptimizationIgnored(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
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
