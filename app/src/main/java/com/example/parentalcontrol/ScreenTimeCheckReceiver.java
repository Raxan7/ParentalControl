// ScreenTimeCheckReceiver.java
package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that performs periodic screen time checks
 * This receiver checks if the daily limit has been exceeded and only then triggers the lock
 * Enhanced with warning notifications and better synchronization
 */
public class ScreenTimeCheckReceiver extends BroadcastReceiver {
    
    // Static notification cooldown tracking to avoid spam from multiple sources
    private static long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN = 2 * 60 * 1000; // 2 minutes between notifications
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("ScreenTimeCheckReceiver", "Performing periodic screen time and bedtime checks");
        
        try {
            // Create screen time manager to perform the check
            ScreenTimeManager screenTimeManager = new ScreenTimeManager(context);
            
            // Enhanced screen time check with warning notifications
            performEnhancedScreenTimeCheck(context, screenTimeManager);
            
            // Also perform bedtime check (integrated approach)
            BedtimeEnforcer bedtimeEnforcer = new BedtimeEnforcer(context);
            boolean bedtimeActive = bedtimeEnforcer.checkAndEnforceBedtime();
            
            if (bedtimeActive) {
                Log.d("ScreenTimeCheckReceiver", "✅ Bedtime enforcement triggered during screen time check");
            }
            
        } catch (Exception e) {
            Log.e("ScreenTimeCheckReceiver", "Error during screen time check", e);
        }
    }
    
    /**
     * Enhanced screen time check with progressive warning notifications
     */
    private void performEnhancedScreenTimeCheck(Context context, ScreenTimeManager screenTimeManager) {
        try {
            ScreenTimeCalculator calculator = new ScreenTimeCalculator(context);
            ScreenTimeRepository screenTimeRepo = new ScreenTimeRepository(context);
            long dailyLimitMinutes = screenTimeRepo.getDailyLimit();
            
            // Get comprehensive screen time data
            ScreenTimeCalculator.ScreenTimeCountdownData countdownData = calculator.getCountdownData();
            long remainingMinutes = countdownData.remainingMinutes;
            long usedMinutes = countdownData.usedMinutes;
            
            Log.d("ScreenTimeCheckReceiver", String.format("Screen time status: %d/%d minutes used, %d remaining", 
                    usedMinutes, dailyLimitMinutes, remainingMinutes));
            
            // Check for different warning states and send appropriate notifications
            if (remainingMinutes <= 0) {
                // Limit exceeded - trigger lock immediately
                Log.d("ScreenTimeCheckReceiver", "🚨 CRITICAL: Screen time limit exceeded - triggering immediate lock");
                
                Intent lockIntent = new Intent(context, LockDeviceReceiver.class);
                lockIntent.putExtra("lock_reason", "screen_time");
                context.sendBroadcast(lockIntent);
                
                // Send critical notification
                sendScreenTimeNotification(context, "Screen Time Limit Reached!", 
                    "Daily limit of " + dailyLimitMinutes + " minutes has been exceeded", 
                    NotificationPriority.CRITICAL);
                    
            } else if (remainingMinutes <= 5) {
                // Critical warning - 5 minutes or less
                Log.d("ScreenTimeCheckReceiver", "⚠️ CRITICAL WARNING: Only " + remainingMinutes + " minutes remaining");
                
                sendScreenTimeNotification(context, "Screen Time Warning - " + remainingMinutes + " min left", 
                    "Your daily screen time limit will be reached soon. Device will lock when limit is reached.", 
                    NotificationPriority.HIGH);
                    
            } else if (remainingMinutes <= 15) {
                // Warning - 15 minutes or less
                Log.d("ScreenTimeCheckReceiver", "⚠️ WARNING: " + remainingMinutes + " minutes remaining");
                
                sendScreenTimeNotification(context, "Screen Time Warning - " + remainingMinutes + " min left", 
                    "You have " + remainingMinutes + " minutes of screen time remaining today.", 
                    NotificationPriority.NORMAL);
                    
            } else if (remainingMinutes <= 30) {
                // Early warning - 30 minutes or less
                Log.d("ScreenTimeCheckReceiver", "ℹ️ INFO: " + remainingMinutes + " minutes remaining");
                
                sendScreenTimeNotification(context, "Screen Time Reminder", 
                    "You have " + remainingMinutes + " minutes remaining of your daily " + dailyLimitMinutes + " minute limit.", 
                    NotificationPriority.LOW);
            } else {
                // Normal state - perform regular check
                screenTimeManager.checkScreenTime(context);
            }
            
        } catch (Exception e) {
            Log.e("ScreenTimeCheckReceiver", "Error in enhanced screen time check", e);
            // Fallback to regular check
            screenTimeManager.checkScreenTime(context);
        }
    }
    
    /**
     * Send screen time notification with different priority levels and cooldown protection
     */
    private void sendScreenTimeNotification(Context context, String title, String message, NotificationPriority priority) {
        try {
            long currentTime = System.currentTimeMillis();
            
            // Check cooldown to prevent notification spam
            if (currentTime - lastNotificationTime < NOTIFICATION_COOLDOWN) {
                Log.d("ScreenTimeCheckReceiver", "⏰ Skipping notification due to cooldown: " + title);
                return;
            }
            
            // Use enhanced AlertNotifier with priority support
            EnhancedAlertNotifier.showScreenTimeNotification(context, title, message, priority);
            lastNotificationTime = currentTime;
            Log.d("ScreenTimeCheckReceiver", "📢 Screen time notification sent: " + title);
        } catch (Exception e) {
            Log.e("ScreenTimeCheckReceiver", "Error sending screen time notification", e);
            // Fallback to basic notification
            AlertNotifier.showNotification(context, title, message);
        }
    }
    
    /**
     * Notification priority levels for different warning states
     */
    public enum NotificationPriority {
        LOW,     // 30+ minutes remaining
        NORMAL,  // 15-30 minutes remaining  
        HIGH,    // 5-15 minutes remaining
        CRITICAL // Limit reached or exceeded
    }
}
