// ServiceManager.java
package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ServiceManager {
    private final Context context;

    public ServiceManager(Context context) {
        this.context = context;
    }

    public void startAllServices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(new Intent(context, ActivityTrackerService.class));
            context.startForegroundService(new Intent(context, DataSyncService.class));
        } else {
            context.startService(new Intent(context, ActivityTrackerService.class));
            context.startService(new Intent(context, DataSyncService.class));
        }
    }

    public void stopAllServices() {
        context.stopService(new Intent(context, ActivityTrackerService.class));
        context.stopService(new Intent(context, DataSyncService.class));
    }

    public void restartServices() {
        stopAllServices();
        startAllServices();
    }
}