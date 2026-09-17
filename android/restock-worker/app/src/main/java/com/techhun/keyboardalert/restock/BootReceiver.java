package com.techhun.keyboardalert.restock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // The process was restarted, so a persisted "running" flag cannot prove
        // that the foreground service is actually alive.
        MonitorPrefs.setRunning(context, false);

        if (ProductStore.enabledCount(context) <= 0) return;
        if (!NotificationAccess.isAllowed(context)) {
            MonitorPrefs.updateStatus(context, "알림 권한 필요");
            return;
        }
        if (!SessionState.hasNaverSession()) {
            MonitorPrefs.updateStatus(context, "로그인 필요");
            return;
        }

        try {
            context.startForegroundService(new Intent(context, MonitorService.class));
        } catch (RuntimeException error) {
            MonitorPrefs.setRunning(context, false);
            MonitorPrefs.updateStatus(context, "재부팅 후 자동 재개 대기");
        }
    }
}
