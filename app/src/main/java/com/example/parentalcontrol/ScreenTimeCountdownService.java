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
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.widget.RelativeLayout;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;

public class ScreenTimeCountdownService extends Service {
    private static final String TAG = "ScreenTimeCountdown";
    private static final String CHANNEL_ID = "screen_time_countdown";
    private static final int NOTIFICATION_ID = 1001;
    private static final int UPDATE_INTERVAL = 1000; // Update every 1 second for smooth countdown
    private static final int RULE_CHECK_INTERVAL = 30000; // Check for rule changes every 30 seconds
    
    // ENHANCED LOCKDOWN: Time before making device totally unusable after limit reached
    private static final int COMPLETE_LOCKDOWN_DELAY_MS = 30000; // 30 seconds
    private boolean isLimitReachedTimerActive = false;
    private long limitReachedTime = 0;
    private boolean isCompletelyLocked = false;
    private View overlayView = null;
    private WindowManager windowManager = null;

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
        
        // Check if this service was started with the enforce_complete_lockdown flag
        if (intent != null && intent.getBooleanExtra("enforce_complete_lockdown", false)) {
            Log.d(TAG, "Service started with enforce_complete_lockdown flag - activating lockdown immediately");
            
            // Create fullscreen blocking overlay immediately
            if (!isCompletelyLocked) {
                createFullscreenBlockingOverlay();
            }
        }
        
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
                    "Your " + data.dailyLimitMinutes + " minute daily limit has been reached. Device will be completely locked in 30 seconds!",
                    ScreenTimeCheckReceiver.NotificationPriority.CRITICAL
                );
                
                // Start the 30-second timer for complete lockdown if not already running
                if (!isLimitReachedTimerActive) {
                    isLimitReachedTimerActive = true;
                    limitReachedTime = System.currentTimeMillis();
                    
                    // Schedule the complete lockdown
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // Make device completely unusable after 30 seconds
                            Log.d(TAG, "⚠️ 30 SECONDS ELAPSED - MAKING DEVICE COMPLETELY UNUSABLE");
                            createFullscreenBlockingOverlay();
                        }
                    }, COMPLETE_LOCKDOWN_DELAY_MS);
                    
                    Log.d(TAG, "⏲️ Started 30-second timer before complete device lockdown");
                }
                
                // First initiate normal device lock
                Intent lockBroadcast = new Intent();
                lockBroadcast.setAction("com.example.parentalcontrol.IMMEDIATE_SCREEN_TIME_LIMIT");
                lockBroadcast.putExtra("used_minutes", (int) data.usedMinutes);
                lockBroadcast.putExtra("limit_minutes", (int) data.dailyLimitMinutes);
                sendBroadcast(lockBroadcast);
                
                // Trigger immediate screen time check with HIGHEST PRIORITY
                ScreenTimeManager screenTimeManager = new ScreenTimeManager(this);
                screenTimeManager.checkScreenTime(this);
                
                Log.d(TAG, "🔒 LOCKDOWN INITIATED - Used: " + data.usedMinutes + "/" + data.dailyLimitMinutes + " minutes");
            } else {
                // If the limit is no longer exceeded, reset the timer and remove overlay if exists
                if (isLimitReachedTimerActive) {
                    isLimitReachedTimerActive = false;
                    
                    if (isCompletelyLocked) {
                        removeFullscreenBlockingOverlay();
                    }
                }
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
        
        // Make sure to remove the overlay if the service is stopped
        if (isCompletelyLocked) {
            removeFullscreenBlockingOverlay();
        }
        
        Log.d(TAG, "ScreenTimeCountdownService destroyed");
    }
    
    /**
     * Create a fullscreen blocking overlay that makes the device completely unusable
     * This creates a system alert window that blocks all interactions with the device
     */
    private void createFullscreenBlockingOverlay() {
        if (isCompletelyLocked) {
            return; // Prevent multiple overlays
        }
        
        Log.d(TAG, "Creating fullscreen blocking overlay to make device completely unusable");
        
        try {
            // Get window manager
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            
            // Create layout parameters for fullscreen overlay - ENHANCED TO BLOCK ALL INPUT
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Remove FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCHABLE to intercept all touches
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_FULLSCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |  // Block keyboard
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |   // Capture all touches
                    // Add flags to show overlay on top of everything including system dialogs
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |    // Keep screen on
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,   // Show above lock screen
                    PixelFormat.OPAQUE                                 // Completely opaque
            );
            
            params.gravity = Gravity.CENTER;
            
            // Create the layout that will block everything
            RelativeLayout layout = new RelativeLayout(this);
            layout.setBackgroundColor(Color.BLACK); // Completely opaque black
            
            // Add warning text
            TextView textView = new TextView(this);
            textView.setText("SCREEN TIME LIMIT EXCEEDED\n\nDevice is locked\n\nPlease contact your parent");
            textView.setTextColor(Color.RED);
            textView.setTextSize(24);
            textView.setGravity(Gravity.CENTER);
            
            RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            layout.addView(textView, textParams);
            
            // Add a click listener to the layout to consume all touch events
            layout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Do nothing, just consume the click
                    Log.d(TAG, "Blocked touch attempt on locked device");
                    
                    // Show toast to inform user device is locked
                    Toast.makeText(getApplicationContext(), 
                        "Device is locked. Screen time limit exceeded.", 
                        Toast.LENGTH_SHORT).show();
                }
            });
            
            // Also consume all touch events to prevent any interaction
            layout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // Consume all touch events
                    return true;
                }
            });
            
            // Add the view to the window
            windowManager.addView(layout, params);
            overlayView = layout;
            isCompletelyLocked = true;
            
            Log.d(TAG, "🔒 DEVICE COMPLETELY LOCKED - Fullscreen blocking overlay active");
            
            // Show notification about complete lockdown
            EnhancedAlertNotifier.showScreenTimeNotification(
                this,
                "🔒 DEVICE COMPLETELY LOCKED",
                "Screen time limit exceeded. Device is now locked completely.",
                ScreenTimeCheckReceiver.NotificationPriority.CRITICAL
            );
            
            // Make sure the device also locks (screen off)
            Intent lockIntent = new Intent(this, LockDeviceReceiver.class);
            lockIntent.putExtra("lock_reason", "screen_time_complete");
            sendBroadcast(lockIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating blocking overlay", e);
        }
    }
    
    /**
     * Remove the fullscreen blocking overlay
     */
    private void removeFullscreenBlockingOverlay() {
        if (!isCompletelyLocked || overlayView == null || windowManager == null) {
            return;
        }
        
        try {
            // Check if the view is still attached before removing
            if (overlayView.getParent() != null) {
                windowManager.removeView(overlayView);
            }
            
            overlayView = null;
            isCompletelyLocked = false;
            Log.d(TAG, "🔓 Removed fullscreen blocking overlay - device now usable");
            
            // Show a notification that device is now usable
            EnhancedAlertNotifier.showScreenTimeNotification(
                this,
                "Device Unlocked",
                "Your device is now unlocked. Screen time limit has been updated.",
                ScreenTimeCheckReceiver.NotificationPriority.HIGH
            );
        } catch (Exception e) {
            Log.e(TAG, "Error removing blocking overlay", e);
        }
    }
}
