// ScreenTimeManager.java
package com.example.parentalcontrol;

import static android.content.Context.MODE_PRIVATE;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ScreenTimeManager {
    private final Context context;
    private final AlarmManager alarmManager;
    private final ScreenTimeRepository screenTimeRepo;
    private final ScreenTimeSync screenTimeSync;

    public ScreenTimeManager(Context context) {
        this.context = context;
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.screenTimeRepo = new ScreenTimeRepository(context);
        this.screenTimeSync = new ScreenTimeSync(context);
    }

    public void checkAndSyncScreenTime() {
        // Calculate and save screen time for current minute
        screenTimeRepo.calculateAndSaveMinuteScreenTime();

        // Sync with backend
        screenTimeSync.syncScreenTime(new ScreenTimeSync.SyncCallback() {
            @Override
            public void onSuccess() {
                Log.d("ScreenTimeManager", "Screen time synced successfully");
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("ScreenTimeManager", "Screen time sync failed", e);
                ErrorHandler.handleApiError(context, e, "screen_time_sync");
            }
        });
    }

    /**
     * Sets a timer-based screen time limit in minutes
     * This creates a timer that starts from current usage and adds maxMinutes
     * ONLY call this when rules have actually changed
     * @param maxMinutes Additional minutes to allow from current usage
     */
    public void setTimerBasedLimit(long maxMinutes) {
        Log.d("ScreenTimeManager", "Setting NEW timer-based limit to: " + maxMinutes + " minutes");
        
        // Use timer-based approach via ScreenTimeCalculator
        ScreenTimeCalculator calculator = new ScreenTimeCalculator(context);
        calculator.setTimerBasedLimit(maxMinutes);
        
        SharedPreferences prefs = context.getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        prefs.edit().putLong("daily_limit_minutes", maxMinutes).apply();

        // CRITICAL FIX: Do NOT call saveScreenTimeRules here - it would overwrite server timestamp
        // The database should already be updated by sync services with proper server timestamp
        // screenTimeRepo.saveScreenTimeRules(maxMinutes); // REMOVED - this was overwriting server timestamp

        // Set repeating alarm for periodic checks
        Intent intent = new Intent(context, ScreenTimeCheckReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Check every 30 seconds for better synchronization with countdown service
        alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                30 * 1000, // 30 second interval (improved synchronization)
                pendingIntent
        );

        Log.d("ScreenTimeManager", "Timer-based limit set successfully. Timer will run for " + maxMinutes + " minutes from current usage.");

        // Set up bedtime enforcement as well
        setupBedtimeEnforcement();

        // Sync rules with backend
        syncScreenTimeRules(maxMinutes);
    }

    /**
     * Updates daily limit in preferences without setting a new timer
     * Use this for regular sync operations where rules haven't changed
     * @param maxMinutes The daily limit in minutes
     */
    public void updateDailyLimitPreference(long maxMinutes) {
        Log.d("ScreenTimeManager", "Updating daily limit preference to: " + maxMinutes + " minutes (no timer reset, no database update)");
        
        SharedPreferences prefs = context.getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        prefs.edit().putLong("daily_limit_minutes", maxMinutes).apply();
        
        // CRITICAL FIX: Do NOT update database here - this would change last_updated timestamp
        // The database should only be updated when server rules actually change
        // screenTimeRepo.saveScreenTimeRules(maxMinutes); // REMOVED - this was causing false change detection
    }

    /**
     * Legacy method - now delegates to setTimerBasedLimit for backward compatibility
     * @deprecated Use setTimerBasedLimit() for new timer-based limits or updateDailyLimitPreference() for updates
     */
    @Deprecated
    public void setDailyLimit(long maxMinutes) {
        Log.w("ScreenTimeManager", "⚠️ DEPRECATED setDailyLimit called - use setTimerBasedLimit() for new limits");
        setTimerBasedLimit(maxMinutes);
    }

    private void syncScreenTimeRules(long maxMinutes) {
        try {
            String deviceId = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);

            JSONObject json = new JSONObject();
            json.put("device_id", deviceId);
            json.put("daily_limit_minutes", maxMinutes);

            RequestBody body = RequestBody.create(json.toString(), AuthService.JSON);
            Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/set-screen-time/")
                    .addHeader("Authorization", "Bearer " +
                            AppController.getInstance().getAuthToken())
                    .post(body)
                    .build();

            new OkHttpClient().newCall(request).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Cancels any existing screen time limits and bedtime enforcement
     */
    public void cancelLimits() {
        // Cancel screen time checks
        Intent intent = new Intent(context, ScreenTimeCheckReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(pendingIntent);
        
        // Cancel bedtime enforcement
        cancelBedtimeEnforcement();
    }    /**
     * Resets the screen time limit and clears all usage data for today
     * This effectively gives the user a fresh start with their screen time
     */
    public void resetScreenTimeLimit() {
        Log.d("ScreenTimeManager", "Resetting screen time limit and usage data");
        
        try {
            // Cancel existing alarms
            cancelLimits();
            
            // Clear today's app usage data
            screenTimeRepo.clearTodayUsageData();
            
            // Reset to default limit (120 minutes = 2 hours)
            setDailyLimit(120);
            
            // Clear SharedPreferences screen time data
            SharedPreferences prefs = context.getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
            prefs.edit()
                .remove("last_screen_time_check")
                .remove("countdown_start_time")
                .remove("countdown_elapsed_minutes")
                .apply();
            
            // Reset the lock flag to allow new checks
            lockInProgress = false;
            
            // Restart bedtime enforcement with default settings
            setupBedtimeEnforcement();
            
            Log.d("ScreenTimeManager", "Screen time limit reset complete - new limit: 120 minutes");
            
        } catch (Exception e) {
            Log.e("ScreenTimeManager", "Error resetting screen time limit", e);
        }
    }

    /**
     * Resets the screen time limit with a new timer-based limit from web interface
     * This method is called when rule changes are detected from the web interface
     * Sets up a new timer from current usage
     */
    public void resetScreenTimeLimitWithNewLimit(long newLimitMinutes) {
        Log.d("ScreenTimeManager", "Setting new timer-based limit from web interface: " + newLimitMinutes + " minutes");
        
        try {
            // Set up new timer-based limit (this will handle the timer setup)
            setTimerBasedLimit(newLimitMinutes);
            
            // Reset the lock flag to allow new checks with updated limit
            lockInProgress = false;
            
            // Cancel and restart alarms with new timing
            cancelLimits();
            
            // Set up screen time monitoring with new limit
            Intent intent = new Intent(context, ScreenTimeCheckReceiver.class);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Check every 30 seconds for better synchronization
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis(),
                    30 * 1000, // 30 second interval
                    pendingIntent
            );
            
            // Restart bedtime enforcement
            setupBedtimeEnforcement();
            
            // Sync the new rules with backend
            syncScreenTimeRules(newLimitMinutes);
            
            Log.d("ScreenTimeManager", "Screen time countdown reset complete with new limit: " + newLimitMinutes + " minutes");
            
            // Show notification about the rule change
            EnhancedAlertNotifier.showRuleUpdateNotification(context, newLimitMinutes);
            
        } catch (Exception e) {
            Log.e("ScreenTimeManager", "Error resetting screen time limit with new limit", e);
        }
    }



    private static boolean lockInProgress = false;
    
    /**
     * Enhanced screen time check with timer-based approach support
     * This method now checks timer-based limits first, then falls back to traditional approach
     */
    public void checkScreenTime(Context context) {
        // Use the new calculator for more accurate calculation
        ScreenTimeCalculator calculator = new ScreenTimeCalculator(context);
        
        // Check for rule updates from web interface FIRST
        ScreenTimeCalculator.ScreenTimeCountdownData countdownData = calculator.getCountdownData();
        if (countdownData.wasUpdated) {
            Log.d("ScreenTimeManager", "🔄 Web interface rule update detected - setting timer-based limit: " + countdownData.dailyLimitMinutes + " minutes");
            // Timer is already set in the calculator, just reset other components
            resetScreenTimeLimitWithNewLimit(countdownData.dailyLimitMinutes);
        }
        
        // Enhanced debugging
        Log.d("ScreenTimeManager", "=== ENHANCED TIMER-BASED SCREEN TIME CHECK START ===");
        
        // Check timer-based limit first
        boolean timerLimitExceeded = calculator.isTimerLimitExceeded();
        long timerRemaining = calculator.getTimerRemainingMinutes();
        
        if (timerRemaining >= 0) {
            // Timer is active
            Log.d("ScreenTimeManager", String.format("🎯 Timer-based check - Remaining: %d min, Exceeded: %s", 
                    timerRemaining, timerLimitExceeded));
            
            if (timerLimitExceeded) {
                triggerDeviceLock(context, "Timer-based screen time limit reached");
                return;
            }
        } else {
            // Fallback to traditional approach
            Log.d("ScreenTimeManager", "📊 Fallback to traditional limit checking");
            
            long dailyLimitMinutes = countdownData.dailyLimitMinutes;
            long actualUsage = calculator.getTodayUsageMinutes();
            boolean traditionalLimitExceeded = actualUsage >= dailyLimitMinutes;
            
            Log.d("ScreenTimeManager", String.format("Traditional check - Usage: %d min, Limit: %d min, Exceeded: %s", 
                    actualUsage, dailyLimitMinutes, traditionalLimitExceeded));
            
            if (traditionalLimitExceeded) {
                triggerDeviceLock(context, "Daily screen time limit exceeded");
                return;
            }
        }
        
        Log.d("ScreenTimeManager", "✅ Screen time within limits");
        // Reset lock flag if we're back within limits
        if (lockInProgress) {
            lockInProgress = false;
            Log.d("ScreenTimeManager", "Lock flag reset - usage is back within limits");
        }
        Log.d("ScreenTimeManager", "=== ENHANCED TIMER-BASED SCREEN TIME CHECK END ===");
    }
    
    /**
     * Helper method to trigger device lock with reason
     */
    private void triggerDeviceLock(Context context, String reason) {
        if (!lockInProgress) {
            lockInProgress = true;
            Log.d("ScreenTimeManager", "🚨 " + reason + " - triggering device lock");
            
            // Clear any existing screen time notifications
            EnhancedAlertNotifier.clearScreenTimeNotifications(context);
            
            // Trigger device lock
            Intent intent = new Intent(context, LockDeviceReceiver.class);
            intent.putExtra("lock_reason", "screen_time");
            context.sendBroadcast(intent);
            
            // Reset lock flag after a delay to prevent spam
            new android.os.Handler().postDelayed(() -> {
                lockInProgress = false;
                Log.d("ScreenTimeManager", "Lock flag reset - ready for new checks");
            }, 30000); // 30 seconds
        } else {
            Log.d("ScreenTimeManager", "Lock already in progress, skipping");
        }
    }

    /**
     * Sets up bedtime enforcement with periodic checks
     */
    public void setupBedtimeEnforcement() {
        Log.d("ScreenTimeManager", "Setting up bedtime enforcement");
        
        // Schedule periodic bedtime checks every 5 minutes
        Intent bedtimeIntent = new Intent(context, BedtimeCheckReceiver.class);
        PendingIntent bedtimePendingIntent = PendingIntent.getBroadcast(
                context,
                1001, // Different request code from screen time
                bedtimeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Check bedtime every 5 minutes (more frequent than screen time for better enforcement)
        alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                5 * 60 * 1000, // 5 minute interval
                bedtimePendingIntent
        );

        Log.d("ScreenTimeManager", "Bedtime enforcement scheduled for periodic checks every 5 minutes");
    }

    /**
     * Cancels bedtime enforcement alarms
     */
    public void cancelBedtimeEnforcement() {
        Intent bedtimeIntent = new Intent(context, BedtimeCheckReceiver.class);
        PendingIntent bedtimePendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                bedtimeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(bedtimePendingIntent);
        Log.d("ScreenTimeManager", "Bedtime enforcement cancelled");
    }

}
