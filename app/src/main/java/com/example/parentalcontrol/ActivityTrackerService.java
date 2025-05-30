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

import org.greenrobot.eventbus.EventBus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ActivityTrackerService extends Service {
    private static final String TAG = "ActivityTrackerService";
    private static final String CHANNEL_ID = "ActivityTrackerChannel";
    private UsageStatsManager usageStatsManager;
    private Handler handler;
    private Runnable trackingRunnable;
    
    // Add tracking state variables
    private String currentForegroundApp = null;
    private long currentAppStartTime = 0;
    private AppUsageRepository repository;
    private long lastEventTime = 0;
    private Map<String, Long> appStartTimes = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        repository = new AppUsageRepository(this);
        lastEventTime = System.currentTimeMillis();

        handler = new Handler();
        trackingRunnable = new Runnable() {
            @Override
            public void run() {
                trackForegroundApp();
                handler.postDelayed(this, 3000); // Check every 3 seconds for more responsive tracking
            }
        };
    }

    @SuppressLint("ForegroundServiceType")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = buildNotification();
        startForeground(1, notification);

        handler.post(trackingRunnable);
        Log.d(TAG, "Service started");
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Activity Tracker",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control")
                .setContentText("Tracking app usage")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        // Save any current session before destroying
        if (currentForegroundApp != null && currentAppStartTime > 0) {
            long endTime = System.currentTimeMillis();
            repository.saveAppUsage(currentForegroundApp, currentAppStartTime, endTime);
            Log.d(TAG, "Saved final session for " + currentForegroundApp + 
                  ": " + (endTime - currentAppStartTime) + "ms");
        }
        
        handler.removeCallbacks(trackingRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void trackForegroundApp() {
        try {
            long currentTime = System.currentTimeMillis();
            long queryStartTime = lastEventTime - 1000; // Overlap by 1 second to catch events
            
            Log.d(TAG, "Querying usage events from " + queryStartTime + " to " + currentTime);

            UsageEvents usageEvents = usageStatsManager.queryEvents(queryStartTime, currentTime);
            UsageEvents.Event event = new UsageEvents.Event();
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                handleUsageEvent(event);
            }
            
            lastEventTime = currentTime;
            
            // Also check if current app is still running (in case no events occurred)
            checkCurrentApp();
            
        } catch (Exception e) {
            Log.e(TAG, "Error tracking app usage", e);
        }
    }
    
    private void handleUsageEvent(UsageEvents.Event event) {
        String packageName = event.getPackageName();
        long eventTime = event.getTimeStamp();
        int eventType = event.getEventType();
        
        Log.d(TAG, "Event: " + packageName + " type: " + eventType + " at " + eventTime);
        
        switch (eventType) {
            case UsageEvents.Event.MOVE_TO_FOREGROUND:
                handleAppMoveToForeground(packageName, eventTime);
                break;
                
            case UsageEvents.Event.MOVE_TO_BACKGROUND:
                handleAppMoveToBackground(packageName, eventTime);
                break;
        }
    }
    
    private void handleAppMoveToForeground(String packageName, long eventTime) {
        // End current session if there's one
        if (currentForegroundApp != null && currentAppStartTime > 0) {
            repository.saveAppUsage(currentForegroundApp, currentAppStartTime, eventTime);
            Log.d(TAG, "Ended session for " + currentForegroundApp + 
                  ": " + (eventTime - currentAppStartTime) + "ms");
        }
        
        // Start new session
        currentForegroundApp = packageName;
        currentAppStartTime = eventTime;
        appStartTimes.put(packageName, eventTime);
        
        Log.d(TAG, "Started session for " + packageName + " at " + eventTime);
    }
    
    private void handleAppMoveToBackground(String packageName, long eventTime) {
        Long startTime = appStartTimes.get(packageName);
        if (startTime != null) {
            repository.saveAppUsage(packageName, startTime, eventTime);
            Log.d(TAG, "Background session for " + packageName + 
                  ": " + (eventTime - startTime) + "ms");
            appStartTimes.remove(packageName);
        }
        
        // Clear current if this was the current app
        if (packageName.equals(currentForegroundApp)) {
            currentForegroundApp = null;
            currentAppStartTime = 0;
        }
    }
    
    private void checkCurrentApp() {
        // If we have a current foreground app but haven't seen events for a while,
        // we might need to save periodic usage to avoid losing long sessions
        if (currentForegroundApp != null && currentAppStartTime > 0) {
            long currentTime = System.currentTimeMillis();
            long sessionDuration = currentTime - currentAppStartTime;
            
            // If session has been running for more than 5 minutes, save intermediate progress
            if (sessionDuration > 5 * 60 * 1000) {
                repository.saveAppUsage(currentForegroundApp, currentAppStartTime, currentTime);
                Log.d(TAG, "Saved intermediate session for " + currentForegroundApp + 
                      ": " + sessionDuration + "ms");
                
                // Reset start time to continue tracking
                currentAppStartTime = currentTime;
            }
        }
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, ActivityTrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}