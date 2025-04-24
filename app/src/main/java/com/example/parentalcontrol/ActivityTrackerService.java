package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.parentalcontrol.AppUsageDatabaseHelper;

import org.greenrobot.eventbus.EventBus;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ActivityTrackerService extends Service {
    private UsageStatsManager usageStatsManager;
    private Handler handler;
    private Runnable runnable;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                trackForegroundApp();
                handler.postDelayed(this, 5000); // Check every 5 seconds
            }
        };
    }

    // In ActivityTrackerService.java
    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification();
        startForeground(1, notification); // Required for Android 8.0+

        handler.post(runnable);
        Log.d("TRACKER", "Service started");
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "tracker_channel",
                    "Activity Tracker",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, "tracker_channel")
                .setContentTitle("Parental Control")
                .setContentText("Tracking app usage")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(runnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // In ActivityTrackerService.java
    private void trackForegroundApp() {
        try {
            long endTime = System.currentTimeMillis();
            long beginTime = endTime - 10000;
            Log.d("TRACKER", "Querying usage stats from " + beginTime + " to " + endTime);

            UsageEvents usageEvents = usageStatsManager.queryEvents(beginTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String lastForegroundApp = null;

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                Log.d("TRACKER", "Found event: " + event.getPackageName() + " type: " + event.getEventType());

                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundApp = event.getPackageName();
                }
            }

            if (lastForegroundApp != null) {
                Log.d("TRACKER", "Tracking app: " + lastForegroundApp);
                AppUsageRepository repository = new AppUsageRepository(this);
                long now = System.currentTimeMillis();
                repository.saveAppUsage(lastForegroundApp, now - 5000, now);
            } else {
                Log.d("TRACKER", "No foreground app detected");
            }
        } catch (Exception e) {
            Log.e("TRACKER", "Error tracking app", e);
        }
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