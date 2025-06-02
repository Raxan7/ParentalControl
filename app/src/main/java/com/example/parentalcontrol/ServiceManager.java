// ServiceManager.java
package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class ServiceManager {
    private static final String TAG = "ServiceManager";
    private final Context context;
    private static ServiceManager instance;

    public ServiceManager(Context context) {
        this.context = context;
    }
    
    public static synchronized ServiceManager getInstance(Context context) {
        if (instance == null) {
            instance = new ServiceManager(context.getApplicationContext());
        }
        return instance;
    }

    public void startAllServices() {
        Log.d(TAG, "Starting all essential services including periodic HTTP sync");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(new Intent(context, ActivityTrackerService.class));
                context.startForegroundService(new Intent(context, DataSyncService.class));
                context.startForegroundService(new Intent(context, BlockingSyncService.class));
                context.startForegroundService(new Intent(context, PeriodicHttpSyncService.class)); // New comprehensive HTTP sync
                context.startForegroundService(new Intent(context, AppBlockerService.class));
                context.startForegroundService(new Intent(context, ScreenTimeCountdownService.class));
            } else {
                context.startService(new Intent(context, ActivityTrackerService.class));
                context.startService(new Intent(context, DataSyncService.class));
                context.startService(new Intent(context, BlockingSyncService.class));
                context.startService(new Intent(context, PeriodicHttpSyncService.class)); // New comprehensive HTTP sync
                context.startService(new Intent(context, AppBlockerService.class));
                context.startService(new Intent(context, ScreenTimeCountdownService.class));
            }
            Log.d(TAG, "All services started successfully including PeriodicHttpSyncService");
        } catch (Exception e) {
            Log.e(TAG, "Error starting services", e);
        }
    }

    public void stopAllServices() {
        Log.d(TAG, "Stopping all services");
        context.stopService(new Intent(context, ActivityTrackerService.class));
        context.stopService(new Intent(context, DataSyncService.class));
        context.stopService(new Intent(context, BlockingSyncService.class));
        context.stopService(new Intent(context, PeriodicHttpSyncService.class)); // Stop HTTP sync service
        context.stopService(new Intent(context, AppBlockerService.class));
        context.stopService(new Intent(context, ScreenTimeCountdownService.class));
    }

    public void restartServices() {
        Log.d(TAG, "Restarting all services");
        stopAllServices();
        // Add a small delay before restarting
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        startAllServices();
    }

    public void ensureServicesRunning() {
        Log.d(TAG, "Ensuring all services are running");
        startAllServices(); // This will start any services that aren't already running
    }
}