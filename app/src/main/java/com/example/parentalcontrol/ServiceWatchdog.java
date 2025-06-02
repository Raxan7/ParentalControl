package com.example.parentalcontrol;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.List;

/**
 * Watchdog service to ensure all essential services remain running
 */
public class ServiceWatchdog {
    private static final String TAG = "ServiceWatchdog";
    private static final long WATCHDOG_INTERVAL = 30000; // 30 seconds
    
    private Context context;
    private Handler handler;
    private boolean isRunning = false;
    
    private final String[] ESSENTIAL_SERVICES = {
        "com.example.parentalcontrol.ActivityTrackerService",
        "com.example.parentalcontrol.DataSyncService",
        "com.example.parentalcontrol.BlockingSyncService",
        "com.example.parentalcontrol.AppBlockerService",
        "com.example.parentalcontrol.ScreenTimeCountdownService"
    };
    
    public ServiceWatchdog(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler();
    }
    
    public void startWatchdog() {
        if (isRunning) {
            Log.d(TAG, "Watchdog already running");
            return;
        }
        
        isRunning = true;
        Log.d(TAG, "Starting service watchdog");
        
        Runnable watchdogTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    checkAndRestartServices();
                    handler.postDelayed(this, WATCHDOG_INTERVAL);
                }
            }
        };
        
        handler.post(watchdogTask);
    }
    
    public void stopWatchdog() {
        Log.d(TAG, "Stopping service watchdog");
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
    }
    
    private void checkAndRestartServices() {
        Log.d(TAG, "Checking service status...");
        
        for (String serviceName : ESSENTIAL_SERVICES) {
            if (!isServiceRunning(serviceName)) {
                Log.w(TAG, "Service " + serviceName + " is not running, attempting to restart");
                restartService(serviceName);
            }
        }
        
        // Also check if accessibility service is enabled
        if (!AppBlockAccessibilityService.isAccessibilityServiceEnabled(context)) {
            Log.w(TAG, "Accessibility service is not enabled");
            // Could send notification to user about this
        }
    }
    
    private boolean isServiceRunning(String serviceName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            
            for (ActivityManager.RunningServiceInfo service : services) {
                if (serviceName.equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if service is running: " + serviceName, e);
        }
        return false;
    }
    
    private void restartService(String serviceName) {
        try {
            Intent serviceIntent = null;
            
            switch (serviceName) {
                case "com.example.parentalcontrol.ActivityTrackerService":
                    serviceIntent = new Intent(context, ActivityTrackerService.class);
                    break;
                case "com.example.parentalcontrol.DataSyncService":
                    serviceIntent = new Intent(context, DataSyncService.class);
                    break;
                case "com.example.parentalcontrol.BlockingSyncService":
                    serviceIntent = new Intent(context, BlockingSyncService.class);
                    break;
                case "com.example.parentalcontrol.AppBlockerService":
                    serviceIntent = new Intent(context, AppBlockerService.class);
                    break;
                case "com.example.parentalcontrol.ScreenTimeCountdownService":
                    serviceIntent = new Intent(context, ScreenTimeCountdownService.class);
                    break;
            }
            
            if (serviceIntent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "Restarted service: " + serviceName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restarting service: " + serviceName, e);
        }
    }
}
