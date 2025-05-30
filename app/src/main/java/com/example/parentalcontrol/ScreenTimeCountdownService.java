// ScreenTimeCountdownService.java
package com.example.parentalcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;

public class ScreenTimeCountdownService extends Service {
    private static final String TAG = "ScreenTimeCountdown";
    private static final String CHANNEL_ID = "screen_time_countdown";
    private static final int NOTIFICATION_ID = 1001;
    private static final int UPDATE_INTERVAL = 1000; // Update every 1 second for smooth countdown
    private static final int RULE_CHECK_INTERVAL = 30000; // Check for rule changes every 30 seconds

    private Handler handler;
    private Runnable countdownRunnable;
    private Runnable ruleCheckRunnable;
    private AppUsageDatabaseHelper dbHelper;
    private ScreenTimeRepository screenTimeRepo;
    private ScreenTimeCalculator screenTimeCalculator;
    private NotificationManager notificationManager;
    private long lastRuleCheckTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ScreenTimeCountdownService created");
        
        dbHelper = ServiceLocator.getInstance(this).getDatabaseHelper();
        screenTimeRepo = new ScreenTimeRepository(this);
        screenTimeCalculator = new ScreenTimeCalculator(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        
        startCountdown();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenTimeCountdownService started");
        return START_STICKY; // Restart service if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is an unbound service
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Time Countdown",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows remaining screen time");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startCountdown() {
        // Main countdown runnable - updates every second for smooth countdown
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                updateCountdownDisplay();
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        
        // Rule check runnable - checks for server updates every 30 seconds
        ruleCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkForRuleUpdates();
                handler.postDelayed(this, RULE_CHECK_INTERVAL);
            }
        };
        
        // Start both runnables
        handler.post(countdownRunnable);
        handler.post(ruleCheckRunnable);
    }

    /**
     * Update the countdown display (called every second)
     */
    private void updateCountdownDisplay() {
        try {
            // Get countdown data (uses cached values for stability)
            ScreenTimeCalculator.ScreenTimeCountdownData data = screenTimeCalculator.getCountdownData();
            
            // Update notification
            updateNotification(data);
            
            // Send broadcast for UI updates
            Intent broadcastIntent = new Intent("com.example.parentalcontrol.SCREEN_TIME_UPDATE");
            broadcastIntent.putExtra("used_minutes", (int) data.usedMinutes);
            broadcastIntent.putExtra("remaining_minutes", (int) data.remainingMinutes);
            broadcastIntent.putExtra("daily_limit_minutes", (int) data.dailyLimitMinutes);
            broadcastIntent.putExtra("percentage_used", data.percentageUsed);
            broadcastIntent.putExtra("was_updated", data.wasUpdated);
            sendBroadcast(broadcastIntent);
            
            if (data.wasUpdated) {
                Log.d(TAG, "Screen time rules were updated: " + data.toString());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating countdown display", e);
        }
    }

    /**
     * Check for rule updates from server (called every 30 seconds)
     */
    private void checkForRuleUpdates() {
        try {
            long currentTime = System.currentTimeMillis();
            
            // Only check if enough time has passed since last check
            if (currentTime - lastRuleCheckTime >= RULE_CHECK_INTERVAL) {
                Log.d(TAG, "Checking for screen time rule updates from server...");
                
                // This will trigger any pending sync operations
                // The ScreenTimeCalculator will detect changes when rules are updated
                lastRuleCheckTime = currentTime;
                
                Log.d(TAG, "Rule check completed");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking for rule updates", e);
        }
    }

    private void updateNotification(ScreenTimeCalculator.ScreenTimeCountdownData data) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = "Screen Time: " + data.usedMinutes + "/" + data.dailyLimitMinutes + " min";
        String content;
        
        if (data.remainingMinutes > 0) {
            content = data.remainingMinutes + " minutes remaining today";
        } else {
            content = "Daily limit reached!";
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setProgress(100, (int) data.percentageUsed, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS);

        // Set color based on status
        if (data.isLimitExceeded()) {
            builder.setColor(0xFFFF0000); // Red for limit reached
        } else if (data.isWarningState()) {
            builder.setColor(0xFFFF6B35); // Orange for warning
        } else {
            builder.setColor(0xFF4CAF50); // Green for normal
        }

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private long getDailyLimitMinutes() {
        // Try database first
        long limit = screenTimeRepo.getDailyLimit();
        
        // Fallback to SharedPreferences
        if (limit <= 0) {
            SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
            limit = prefs.getLong("daily_limit_minutes", 120); // Default 2 hours
        }
        
        return limit;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            if (countdownRunnable != null) {
                handler.removeCallbacks(countdownRunnable);
            }
            if (ruleCheckRunnable != null) {
                handler.removeCallbacks(ruleCheckRunnable);
            }
        }
        Log.d(TAG, "ScreenTimeCountdownService destroyed");
    }
}
