package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Boot receiver triggered with action: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            // Start services immediately
            startServicesImmediately(context);
            
            // Also schedule via WorkManager as backup
            scheduleServiceStartup(context);
            
            // Request to ignore battery optimizations
            requestIgnoreBatteryOptimizations(context);
        }
    }

    private void startServicesImmediately(Context context) {
        Log.d(TAG, "Starting services immediately after boot");
        
        try {
            // Start all essential services immediately
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(new Intent(context, ActivityTrackerService.class));
                context.startForegroundService(new Intent(context, DataSyncService.class));
                context.startForegroundService(new Intent(context, BlockingSyncService.class));
                context.startForegroundService(new Intent(context, AppBlockerService.class));
                context.startForegroundService(new Intent(context, ScreenTimeCountdownService.class));
            } else {
                context.startService(new Intent(context, ActivityTrackerService.class));
                context.startService(new Intent(context, DataSyncService.class));
                context.startService(new Intent(context, BlockingSyncService.class));
                context.startService(new Intent(context, AppBlockerService.class));
                context.startService(new Intent(context, ScreenTimeCountdownService.class));
            }
            Log.d(TAG, "All services started successfully after boot");
        } catch (Exception e) {
            Log.e(TAG, "Error starting services after boot", e);
        }
    }

    private void scheduleServiceStartup(Context context) {
        Log.d(TAG, "Scheduling service startup via WorkManager");
        
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest startupWork = new OneTimeWorkRequest.Builder(ServiceStartupWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        "ServiceStartupWork",
                        ExistingWorkPolicy.REPLACE,
                        startupWork
                );
    }
    
    private void requestIgnoreBatteryOptimizations(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting battery optimization ignore", e);
        }
    }
}