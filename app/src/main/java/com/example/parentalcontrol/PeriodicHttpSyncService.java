package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.sqlite.SQLiteDatabase;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Comprehensive periodic HTTP sync service that ensures the app makes requests 
 * to the web interface every 10 seconds for all sync operations
 */
public class PeriodicHttpSyncService extends Service {
    private static final String TAG = "PeriodicHttpSyncService";
    private static final String CHANNEL_ID = "periodic_http_sync_channel";
    private static final int NOTIFICATION_ID = 4001;
    
    // Fixed 10-second interval as requested
    private static final long SYNC_INTERVAL = 10000; // 10 seconds
    private static final long LOW_BATTERY_INTERVAL = 20000; // 20 seconds when battery is low
    private static final long CRITICAL_BATTERY_INTERVAL = 30000; // 30 seconds when battery is critical
    
    private Handler syncHandler;
    private boolean isRunning = false;
    private long currentSyncInterval = SYNC_INTERVAL;
    private int syncCycleCounter = 0;
    
    // Battery monitoring
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = level * 100 / (float) scale;
            
            adjustSyncInterval((int) batteryPct);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PeriodicHttpSyncService created - will sync every 10 seconds");
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createForegroundNotification());
        
        syncHandler = new Handler(Looper.getMainLooper());
        
        // Register battery receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);
        
        Log.d(TAG, "PeriodicHttpSyncService initialized successfully");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "PeriodicHttpSyncService started - beginning 10-second HTTP sync cycle");
        
        if (!isRunning) {
            isRunning = true;
            startPeriodicSync();
        }
        
        return START_STICKY; // Restart if killed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "PeriodicHttpSyncService destroyed - stopping HTTP sync");
        isRunning = false;
        
        if (syncHandler != null) {
            syncHandler.removeCallbacksAndMessages(null);
        }
        
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering battery receiver", e);
        }
        
        // Restart the service to maintain continuous sync
        restartService();
        super.onDestroy();
    }

    private void startPeriodicSync() {
        @SuppressLint("HardwareIds")
        final String deviceId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ANDROID_ID);
        
        Runnable syncTask = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    performComprehensiveSync(deviceId);
                    syncHandler.postDelayed(this, currentSyncInterval);
                }
            }
        };
        
        Log.d(TAG, "Starting periodic HTTP sync with " + currentSyncInterval + "ms interval");
        syncHandler.post(syncTask);
    }

    private void performComprehensiveSync(String deviceId) {
        syncCycleCounter++;
        Log.d(TAG, "=== HTTP SYNC CYCLE #" + syncCycleCounter + " ===");
        Log.d(TAG, "Making HTTP requests to web interface every " + (currentSyncInterval / 1000) + " seconds");
        
        String authToken = AppController.getInstance().getAuthToken();
        
        if (authToken == null || authToken.isEmpty()) {
            Log.w(TAG, "❌ Cannot perform HTTP sync - no authentication token");
            Log.w(TAG, "   User needs to log in through MainActivity to enable HTTP requests");
            return;
        }

        Log.d(TAG, "✓ Auth token available - performing HTTP requests to web interface");
        
        // Perform all sync operations in sequence to ensure HTTP requests are made
        new Thread(() -> {
            try {
                // 1. Sync blocked apps (primary sync operation)
                syncBlockedApps(deviceId, authToken);
                
                // 2. Sync screen time rules every 3rd cycle (30 seconds)
                if (syncCycleCounter % 3 == 0) {
                    syncScreenTimeRules(deviceId, authToken);
                }
                
                // 3. Sync app usage data every 6th cycle (60 seconds)
                if (syncCycleCounter % 6 == 0) {
                    syncAppUsageData(authToken);
                }
                
                // 4. Keep-alive ping every cycle to maintain connection
                performKeepAlivePing(deviceId, authToken);
                
            } catch (Exception e) {
                Log.e(TAG, "Error during comprehensive sync", e);
            }
        }).start();
    }

    private void syncBlockedApps(String deviceId, String authToken) {
        try {
            Log.d(TAG, "🔗 Making HTTP request to sync blocked apps...");
            
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();            Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/force_sync_blocked_apps/" + deviceId + "/")
                    .addHeader("Authorization", "Bearer " + authToken)
                    .post(okhttp3.RequestBody.create(new byte[0], null))
                    .build();

            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                Log.d(TAG, "✅ Blocked apps HTTP request successful: " + response.code());
                Log.d(TAG, "Response size: " + responseBody.length() + " bytes");
            } else {
                Log.w(TAG, "⚠️ Blocked apps HTTP request failed: " + response.code());
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Blocked apps HTTP request error", e);
        }
    }

    private void syncScreenTimeRules(String deviceId, String authToken) {
        try {
            Log.d(TAG, "🔗 Making HTTP request to sync screen time rules and bedtime data...");
            
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/get-screen-time-rules/" + deviceId + "/")
                    .addHeader("Authorization", "Bearer " + authToken)
                    .build();

            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                Log.d(TAG, "✅ Screen time rules HTTP request successful: " + response.code());
                
                // Parse and save the response
                JSONObject json = new JSONObject(responseBody);
                
                // Initialize default daily limit
                long dailyLimit = 120; // Default value
                
                // Handle daily limit
                if (json.has("daily_limit_minutes")) {
                    dailyLimit = json.getLong("daily_limit_minutes");
                    Log.d(TAG, "Updated daily screen time limit: " + dailyLimit + " minutes");
                    
                    // Save to shared preferences
                    getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE)
                            .edit()
                            .putLong("daily_limit_minutes", dailyLimit)
                            .apply();
                }
                
                // Handle bedtime data
                String bedtimeStart = json.optString("bedtime_start", null);
                String bedtimeEnd = json.optString("bedtime_end", null);
                
                if (bedtimeStart != null && !bedtimeStart.equals("null") && !bedtimeStart.trim().isEmpty() &&
                    bedtimeEnd != null && !bedtimeEnd.equals("null") && !bedtimeEnd.trim().isEmpty()) {
                    
                    Log.d(TAG, "Updated bedtime rules: " + bedtimeStart + " to " + bedtimeEnd);
                    
                    // Save bedtime data to database
                    saveBedtimeRulesToDatabase(dailyLimit, bedtimeStart, bedtimeEnd);
                } else {
                    Log.d(TAG, "No bedtime rules found or bedtime rules are empty");
                    
                    // Save without bedtime data
                    saveBedtimeRulesToDatabase(dailyLimit, null, null);
                }
                
            } else {
                Log.w(TAG, "⚠️ Screen time rules HTTP request failed: " + response.code());
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Screen time rules HTTP request error", e);
        }
    }

    private void syncAppUsageData(String authToken) {
        try {
            Log.d(TAG, "🔗 Making HTTP request to sync app usage data...");
            
            // Use existing DataSync to perform app usage sync
            DataSync.syncAppUsage(this, authToken, new DataSync.SyncCallback() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "✅ App usage data HTTP sync successful");
                }

                @Override
                public void onFailure(Exception e) {
                    Log.w(TAG, "❌ App usage data HTTP sync failed", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ App usage data sync error", e);
        }
    }

    private void performKeepAlivePing(String deviceId, String authToken) {
        try {
            Log.d(TAG, "🔗 Making HTTP keep-alive ping to web interface...");
            
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/device-status/" + deviceId + "/")
                    .addHeader("Authorization", "Bearer " + authToken)
                    .build();

            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                Log.d(TAG, "✅ Keep-alive HTTP ping successful: " + response.code());
            } else {
                Log.w(TAG, "⚠️ Keep-alive HTTP ping failed: " + response.code());
            }
        } catch (Exception e) {
            Log.d(TAG, "Keep-alive ping failed (expected if endpoint doesn't exist): " + e.getMessage());
        }
    }

    private void adjustSyncInterval(int batteryPercent) {
        long newInterval;
        
        if (batteryPercent <= 15) {
            newInterval = CRITICAL_BATTERY_INTERVAL;
        } else if (batteryPercent <= 30) {
            newInterval = LOW_BATTERY_INTERVAL;
        } else {
            newInterval = SYNC_INTERVAL;
        }
        
        if (currentSyncInterval != newInterval) {
            Log.d(TAG, "Adjusting HTTP sync interval based on battery (" + 
                  batteryPercent + "%): " + (newInterval / 1000) + " seconds");
            currentSyncInterval = newInterval;
        }
    }

    private void restartService() {
        Log.d(TAG, "Restarting PeriodicHttpSyncService to maintain continuous HTTP sync");
        Intent restartIntent = new Intent(this, PeriodicHttpSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "Periodic HTTP Sync";
            String description = "Makes HTTP requests to web interface every 10 seconds";
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control HTTP Sync")
                .setContentText("Making requests to web interface every 10 seconds")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
    
    /**
     * Save bedtime rules to local database for enforcement
     */
    private void saveBedtimeRulesToDatabase(long dailyLimitMinutes, String bedtimeStart, String bedtimeEnd) {
        try {
            AppUsageDatabaseHelper dbHelper = ServiceLocator.getInstance(this).getDatabaseHelper();
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            
            // Insert new rule (the database helper should handle this)
            ContentValues values = new ContentValues();
            values.put("daily_limit_minutes", dailyLimitMinutes);
            values.put("bedtime_start", bedtimeStart);
            values.put("bedtime_end", bedtimeEnd);
            values.put("last_updated", System.currentTimeMillis());
            
            long result = db.insert("screen_time_rules", null, values);
            db.close();
            
            if (result != -1) {
                Log.d(TAG, "✅ Bedtime rules saved to database successfully");
            } else {
                Log.w(TAG, "⚠️ Failed to save bedtime rules to database");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving bedtime rules to database", e);
        }
    }
}
