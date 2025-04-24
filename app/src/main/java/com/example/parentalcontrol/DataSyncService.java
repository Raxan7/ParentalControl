package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class DataSyncService extends Service {
    private static final String CHANNEL_ID = "data_sync_channel";
    private static final int NOTIFICATION_ID = 2;
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL = 5000; // 5 seconds for testing

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
        if (authToken != null && !authToken.isEmpty()) {
            DataSync.syncAppUsage(this, authToken, new DataSync.SyncCallback() {
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