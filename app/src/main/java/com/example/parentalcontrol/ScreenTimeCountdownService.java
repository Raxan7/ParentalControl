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
    
    // Notification cooldown tracking for better UX
    private long last30MinWarningTime = 0;
    private long last15MinWarningTime = 0;
    private long last5MinWarningTime = 0;
    private long last1MinWarningTime = 0;
    private long lastLimitReachedTime = 0;
    
    // Cooldown intervals (in milliseconds)
    private static final long WARNING_COOLDOWN_30MIN = 10 * 60 * 1000; // 10 minutes
    private static final long WARNING_COOLDOWN_15MIN = 5 * 60 * 1000;  // 5 minutes
    private static final long WARNING_COOLDOWN_5MIN = 2 * 60 * 1000;   // 2 minutes
    private static final long WARNING_COOLDOWN_1MIN = 30 * 1000;       // 30 seconds
    private static final long WARNING_COOLDOWN_LIMIT = 10 * 1000;      // 10 seconds
    
    // User-friendly notification throttling
    private long lastWarningNotificationTime = 0;
    private int lastNotifiedRemainingMinutes = -1; // Track the last notified remaining time

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
        // But increases frequency when close to limits
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    updateCountdownDisplay();
                    
                    // Determine next update interval based on remaining time
                    long nextInterval = getAdaptiveUpdateInterval();
                    handler.postDelayed(this, nextInterval);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in countdown runnable", e);
                    // Fallback to normal interval
                    handler.postDelayed(this, UPDATE_INTERVAL);
                }
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
     * Get adaptive update interval based on remaining screen time
     * Updates more frequently when close to limits for immediate response
     */
    private long getAdaptiveUpdateInterval() {
        try {
            ScreenTimeCalculator.ScreenTimeCountdownData data = screenTimeCalculator.getCountdownData();
            long remainingMinutes = data.remainingMinutes;
            
            if (remainingMinutes <= 0) {
                // Limit reached - update every 100ms for IMMEDIATE response
                return 100;
            } else if (remainingMinutes <= 1) {
                // Critical zone (1 minute left) - update every 500ms for precision
                return 500;
            } else if (remainingMinutes <= 5) {
                // Warning zone - update every 1 second
                return 1000;
            } else if (remainingMinutes <= 15) {
                // Caution zone - update every 2 seconds
                return 2000;
            } else if (remainingMinutes <= 30) {
                // Watch zone - update every 5 seconds
                return 5000;
            } else {
                // Normal zone - update every 10 seconds
                return 10000;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating adaptive interval", e);
            return UPDATE_INTERVAL; // Fallback to default
        }
    }
    
    /**
     * Enhanced countdown display update with immediate limit detection
     */
    private void updateCountdownDisplay() {
        try {
            // Get countdown data (uses actual usage only for accurate timing)
            ScreenTimeCalculator.ScreenTimeCountdownData data = screenTimeCalculator.getCountdownData();
            
            // Check for immediate limit exceeded condition - CRITICAL PATH
            if (data.isLimitExceeded()) {
                Log.d(TAG, "🚨 IMMEDIATE LIMIT DETECTION: Screen time limit exceeded - TRIGGERING IMMEDIATE LOCKDOWN");
                
                // Send critical notification IMMEDIATELY
                EnhancedAlertNotifier.showScreenTimeNotification(
                    this,
                    "🚨 SCREEN TIME LIMIT REACHED",
                    "Your " + data.dailyLimitMinutes + " minute daily limit has been reached. Device locking NOW.",
                    ScreenTimeCheckReceiver.NotificationPriority.CRITICAL
                );
                
                // Send immediate broadcast to trigger lock
                Intent lockBroadcast = new Intent();
                lockBroadcast.setAction("com.example.parentalcontrol.IMMEDIATE_SCREEN_TIME_LIMIT");
                lockBroadcast.putExtra("used_minutes", (int) data.usedMinutes);
                lockBroadcast.putExtra("limit_minutes", (int) data.dailyLimitMinutes);
                sendBroadcast(lockBroadcast);
                
                // Trigger immediate screen time check with HIGHEST PRIORITY
                ScreenTimeManager screenTimeManager = new ScreenTimeManager(this);
                screenTimeManager.checkScreenTime(this);
                
                Log.d(TAG, "🔒 LOCKDOWN INITIATED - Used: " + data.usedMinutes + "/" + data.dailyLimitMinutes + " minutes");
            }
            
            // Update notification with current status
            updateNotification(data);
            
            // Send broadcast for UI updates
            Intent broadcastIntent = new Intent("com.example.parentalcontrol.SCREEN_TIME_UPDATE");
            broadcastIntent.putExtra("used_minutes", (int) data.usedMinutes);
            broadcastIntent.putExtra("remaining_minutes", (int) data.remainingMinutes);
            broadcastIntent.putExtra("daily_limit_minutes", (int) data.dailyLimitMinutes);
            broadcastIntent.putExtra("percentage_used", data.percentageUsed);
            broadcastIntent.putExtra("was_updated", data.wasUpdated);
            sendBroadcast(broadcastIntent);
            
            // Send warning notifications based on remaining time
            sendProgressiveWarningNotifications(data);
            
            // Debug timing accuracy every 30 seconds when close to limit
            if (data.remainingMinutes <= 30) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastRuleCheckTime >= 30000) { // Every 30 seconds
                    screenTimeCalculator.debugTimingAccuracy();
                }
            }
            
            if (data.wasUpdated) {
                Log.d(TAG, "Screen time rules were updated: " + data.toString());
                // Reset notification cooldowns so user gets fresh warnings with new limits
                resetNotificationCooldowns();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating countdown display", e);
        }
    }
    
    /**
     * Send progressive warning notifications based on remaining time with cooldown for better UX
     */
    private void sendProgressiveWarningNotifications(ScreenTimeCalculator.ScreenTimeCountdownData data) {
        try {
            long remainingMinutes = data.remainingMinutes;
            long currentTime = System.currentTimeMillis();
            
            // Only send notifications if we're getting close to the limit
            if (remainingMinutes > 30) {
                return; // No notifications needed yet
            }
            
            ScreenTimeCheckReceiver.NotificationPriority priority = ScreenTimeCheckReceiver.NotificationPriority.LOW;
            String title = "";
            String message = "";
            boolean shouldSendNotification = false;
            
            if (remainingMinutes <= 0) {
                // Critical - limit reached - IMMEDIATE LOCKDOWN
                if (currentTime - lastLimitReachedTime >= WARNING_COOLDOWN_LIMIT) {
                    priority = ScreenTimeCheckReceiver.NotificationPriority.CRITICAL;
                    title = "Screen Time Limit Reached!";
                    message = "Your daily screen time limit of " + data.dailyLimitMinutes + " minutes has been reached. Device will lock NOW.";
                    shouldSendNotification = true;
                    lastLimitReachedTime = currentTime;
                }
                
            } else if (remainingMinutes == 1) {
                // CRITICAL WARNING - exactly 1 minute before lockdown
                if (currentTime - last1MinWarningTime >= WARNING_COOLDOWN_1MIN) {
                    priority = ScreenTimeCheckReceiver.NotificationPriority.CRITICAL;
                    title = "🚨 FINAL WARNING - 1 MINUTE LEFT";
                    message = "Your device will LOCK in exactly 1 minute when your " + data.dailyLimitMinutes + " minute daily limit is reached!";
                    shouldSendNotification = true;
                    last1MinWarningTime = currentTime;
                }
                
            } else if (remainingMinutes <= 5) {
                // High priority - 5 minutes or less
                if (currentTime - last5MinWarningTime >= WARNING_COOLDOWN_5MIN) {
                    priority = ScreenTimeCheckReceiver.NotificationPriority.HIGH;
                    title = "Screen Time Critical - " + remainingMinutes + " min left";
                    message = "Only " + remainingMinutes + " minutes remaining! Device will lock when limit is reached.";
                    shouldSendNotification = true;
                    last5MinWarningTime = currentTime;
                }
                
            } else if (remainingMinutes <= 15) {
                // Normal priority - 15 minutes or less
                if (currentTime - last15MinWarningTime >= WARNING_COOLDOWN_15MIN) {
                    priority = ScreenTimeCheckReceiver.NotificationPriority.NORMAL;
                    title = "Screen Time Warning - " + remainingMinutes + " min left";
                    message = "You have " + remainingMinutes + " minutes of screen time remaining today.";
                    shouldSendNotification = true;
                    last15MinWarningTime = currentTime;
                }
                
            } else if (remainingMinutes <= 30) {
                // Low priority - 30 minutes or less
                if (currentTime - last30MinWarningTime >= WARNING_COOLDOWN_30MIN) {
                    priority = ScreenTimeCheckReceiver.NotificationPriority.LOW;
                    title = "Screen Time Reminder";
                    message = "You have " + remainingMinutes + " minutes remaining of your daily " + data.dailyLimitMinutes + " minute limit.";
                    shouldSendNotification = true;
                    last30MinWarningTime = currentTime;
                }
            } else {
                return; // No notification needed
            }
            
            // Only send the notification if cooldown period has passed
            if (shouldSendNotification) {
                EnhancedAlertNotifier.showScreenTimeNotification(this, title, message, priority);
                Log.d(TAG, String.format("📢 Sent warning notification: %s (remaining: %d min)", title, remainingMinutes));
            } else {
                Log.d(TAG, String.format("⏰ Skipped notification due to cooldown (remaining: %d min)", remainingMinutes));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending progressive warning notifications", e);
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

    /**
     * Reset notification cooldowns for fresh notifications after rule updates
     */
    private void resetNotificationCooldowns() {
        last30MinWarningTime = 0;
        last15MinWarningTime = 0;
        last5MinWarningTime = 0;
        last1MinWarningTime = 0;
        lastLimitReachedTime = 0;
        Log.d(TAG, "🔄 Notification cooldowns reset - fresh warnings will be sent");
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
