package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.Request;

public class DataSyncService extends Service {
    private static final String CHANNEL_ID = "data_sync_channel";
    private static final int NOTIFICATION_ID = 2;
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL = 10000; // 10 seconds - consistent with BlockingSyncService

    @SuppressLint("ForegroundServiceType")
    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                performSync();
                syncHandler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        syncHandler.post(syncRunnable);
    }

    private void performSync() {
        String authToken = AppController.getInstance().getAuthToken();
        Log.d("DataSyncService", "Attempting to perform sync. Auth token available: " + (authToken != null && !authToken.isEmpty()));
        
        if (authToken != null && !authToken.isEmpty()) {
            Log.d("DataSyncService", "Starting sync with auth token: " + authToken.substring(0, Math.min(10, authToken.length())) + "...");
            // Sync screen time rules first
            syncScreenTimeRules(authToken, new DataSync.SyncCallback() {
                @Override
                public void onSuccess() {
                    // Then sync app usage
                    DataSync.syncAppUsage(DataSyncService.this, authToken, new DataSync.SyncCallback() {
                        @Override
                        public void onSuccess() {
                            Log.d("DataSyncService", "App usage sync completed");
                            // Finally sync screen time data
                            new ScreenTimeManager(DataSyncService.this).checkAndSyncScreenTime();
                        }

                        @Override
                        public void onFailure(Exception e) {
                            Log.e("DataSyncService", "App usage sync failed", e);
                            ErrorHandler.handleApiError(DataSyncService.this, e, "data_sync");
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e("DataSyncService", "Screen time rules sync failed", e);
                    ErrorHandler.handleApiError(DataSyncService.this, e, "screen_time_rules_sync");
                }
            });
        } else {
            Log.w("DataSyncService", "Cannot perform sync - no authentication token available. User needs to log in.");
            
            // Try to recover authentication if refresh token is available
            String refreshToken = AppController.getInstance().getRefreshToken();
            if (refreshToken != null && !refreshToken.isEmpty()) {
                Log.d("DataSyncService", "Attempting to refresh authentication token");
                AuthService.refreshToken(refreshToken, new AuthService.AuthCallback() {
                    @Override
                    public void onSuccess(String accessToken, String refreshToken) {
                        Log.d("DataSyncService", "Authentication token refreshed successfully");
                        // Retry sync after successful refresh
                        performSync();
                    }

                    @Override
                    public void onFailure(Exception e) {
                        Log.e("DataSyncService", "Failed to refresh authentication token", e);
                    }
                });
            }
        }
    }    private void syncScreenTimeRules(String authToken, DataSync.SyncCallback callback) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );

                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/get-screen-time-rules/" + deviceId + "/")
                        .addHeader("Authorization", "Bearer " + authToken)
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);

                    // Check if there are changes to sync using server-side sync flag
                    boolean hasChanges = json.optBoolean("has_changes", false);
                    
                    Log.d("DataSyncService", String.format("Server sync flag response - has_changes: %s", hasChanges));

                    if (hasChanges) {
                        long dailyLimit = json.getLong("daily_limit_minutes");
                        
                        Log.d("DataSyncService", "🔄 Server has unsynced screen time changes - applying new rules");
                        Log.d("DataSyncService", String.format("   New daily limit: %d minutes", dailyLimit));

                        // Save new values to shared preferences
                        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
                        prefs.edit()
                            .putLong("daily_limit_minutes", dailyLimit)
                            .apply();

                        // Update the database with new rules
                        AppUsageDatabaseHelper dbHelper = ServiceLocator.getInstance(this).getDatabaseHelper();
                        android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                        
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put("daily_limit_minutes", dailyLimit);
                        values.put("last_updated", System.currentTimeMillis()); // Mark as updated locally
                        
                        int rowsUpdated = db.update("screen_time_rules", values, "id = ?", new String[]{"1"});
                        if (rowsUpdated == 0) {
                            // Insert if no rows exist
                            values.put("id", 1);
                            db.insert("screen_time_rules", null, values);
                            Log.d("DataSyncService", "📝 Inserted new screen time rule");
                        } else {
                            Log.d("DataSyncService", "📝 Updated screen time rule");
                        }
                        db.close();

                        // Update the screen time manager with new timer-based limit
                        // (Server already marked as synced when we made the request)
                        ScreenTimeManager screenTimeManager = new ScreenTimeManager(this);
                        screenTimeManager.setTimerBasedLimit(dailyLimit);
                    } else {
                        Log.d("DataSyncService", "✅ No unsynced screen time changes from server");
                    }

                    new Handler(Looper.getMainLooper()).post(callback::onSuccess);
                } else {
                    throw new IOException("Failed to get screen time rules: " + response.message());
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Data Sync Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control")
                .setContentText("Syncing data in background")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onActivityEvent(ActivityEvent event) {
        DataSync.syncAppUsage(this,
                AppController.getInstance().getAuthToken(),
                new DataSync.SyncCallback() {
                    @Override
                    public void onSuccess() {
                        Log.d("DataSyncService", "Sync completed successfully");
                    }

                    @Override
                    public void onFailure(Exception e) {
                        Log.e("DataSyncService", "Sync failed", e);
                        ErrorHandler.handleApiError(DataSyncService.this, e, "data_sync");
                    }
                });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        if (syncHandler != null) {
            syncHandler.removeCallbacks(syncRunnable);
        }
        super.onDestroy();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, DataSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}