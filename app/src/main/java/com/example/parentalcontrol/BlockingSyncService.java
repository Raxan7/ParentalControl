package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Background service that watches for web blocking updates
 * and triggers immediate enforcement
 */
public class BlockingSyncService extends Service {
    private static final String TAG = "BlockingSyncService";
    private static final String CHANNEL_ID = "blocking_sync_channel";
    private static final int NOTIFICATION_ID = 3001;
    
    // Different poll intervals based on battery level - Updated for 10 second interval
    private static final int POLL_INTERVAL_NORMAL = 10000;   // 10 seconds (as requested)
    private static final int POLL_INTERVAL_LOW_BATTERY = 20000;  // 20 seconds
    private static final int POLL_INTERVAL_CRITICAL = 30000; // 30 seconds
    
    // Service watchdog intervals
    private static final int WATCHDOG_INTERVAL = 60000; // 1 minute
    
    private Handler handler;
    private Handler watchdogHandler;
    private boolean isRunning = false;
    private int currentPollInterval = POLL_INTERVAL_NORMAL;
    private boolean isLowPowerMode = false;
    
    // Battery level monitoring
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int batteryPct = level * 100 / scale;
                
                adjustPollingInterval(batteryPct);
            } else if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction())) {
                isLowPowerMode = true;
                currentPollInterval = POLL_INTERVAL_LOW_BATTERY;
                Log.d(TAG, "Battery low, reducing polling frequency to " + currentPollInterval + "ms");
            } else if (Intent.ACTION_BATTERY_OKAY.equals(intent.getAction())) {
                isLowPowerMode = false;
                currentPollInterval = POLL_INTERVAL_NORMAL;
                Log.d(TAG, "Battery okay, resuming normal polling frequency: " + currentPollInterval + "ms");
            }
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BlockingSyncService created");
        handler = new Handler();
        watchdogHandler = new Handler();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createForegroundNotification());
        
        // Register battery level receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        registerReceiver(batteryReceiver, filter);
        
        // Start service watchdog
        startServiceWatchdog();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BlockingSyncService started");
        
        if (!isRunning) {
            isRunning = true;
            startPolling();
        }
        
        // If killed, restart
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "BlockingSyncService destroyed - attempting restart");
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        watchdogHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(batteryReceiver);
        
        // Restart service immediately
        restartService();
        
        super.onDestroy();
    }
    
    private void restartService() {
        Log.d(TAG, "Restarting BlockingSyncService");
        Intent restartIntent = new Intent(this, BlockingSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }
    
    private void startServiceWatchdog() {
        Runnable watchdogTask = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Service watchdog check - ensuring all services are running");
                ensureAllServicesRunning();
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
            }
        };
        watchdogHandler.post(watchdogTask);
    }
    
    private void ensureAllServicesRunning() {
        Context context = getApplicationContext();
        ServiceManager serviceManager = new ServiceManager(context);
        serviceManager.ensureServicesRunning();
    }
    
    private void adjustPollingInterval(int batteryPercent) {
        int newInterval;
        
        if (batteryPercent <= 15) {
            // Critical battery
            newInterval = POLL_INTERVAL_CRITICAL;
        } else if (batteryPercent <= 30) {
            // Low battery
            newInterval = POLL_INTERVAL_LOW_BATTERY;
        } else {
            // Normal battery
            newInterval = POLL_INTERVAL_NORMAL;
        }
        
        // Only log if interval changed
        if (currentPollInterval != newInterval) {
            Log.d(TAG, "Adjusting polling interval based on battery level (" + 
                  batteryPercent + "%): " + newInterval + "ms");
            currentPollInterval = newInterval;
        }
    }
    
    private void startPolling() {
        @SuppressLint("HardwareIds")
        final String deviceId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ANDROID_ID);
        
        Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    checkForBlockingUpdates(deviceId);
                    handler.postDelayed(this, currentPollInterval);
                }
            }
        };
        
        handler.post(pollTask);
    }
    
    private void checkForBlockingUpdates(String deviceId) {
        // Direct sync instead of using WorkManager to achieve 10-second polling
        Log.d(TAG, "Checking for blocking updates every 10 seconds...");
        
        // Use the existing BlockedAppsManager to force immediate sync
        // This will call the force_sync_blocked_apps endpoint and update local database
        new Thread(() -> {
            try {
                BlockedAppsManager.forceImmediateSync(this, deviceId);
            } catch (Exception e) {
                Log.e(TAG, "Error during immediate sync in service", e);
            }
        }).start();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "App Blocking Sync";
            String description = "Monitors for app blocking updates from web interface";
            int importance = NotificationManager.IMPORTANCE_LOW;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createForegroundNotification() {
        // Intent to open the main app when notification is tapped
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 
                PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring for app blocking changes")
                .setSmallIcon(R.drawable.ic_block)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
