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
     * Sets a screen time limit in minutes
     *
     * @param maxMinutes Maximum allowed screen time in minutes
     */
    public void setDailyLimit(long maxMinutes) {
        Log.d("ScreenTimeManager", "Setting daily limit to: " + maxMinutes + " minutes");
        
        SharedPreferences prefs = context.getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        prefs.edit().putLong("daily_limit_minutes", maxMinutes).apply();

        // Save to local database
        screenTimeRepo.saveScreenTimeRules(maxMinutes);

        // Set repeating alarm for periodic checks (for testing, every minute)
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

        Log.d("ScreenTimeManager", "Daily limit set successfully. Alarm scheduled for periodic checks.");

        // Set up bedtime enforcement as well
        setupBedtimeEnforcement();

        // Sync rules with backend
        syncScreenTimeRules(maxMinutes);
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



    private static boolean lockInProgress = false;
    
    /**
     * Enhanced screen time check with improved synchronization
     * This method now performs more frequent checks and better handles timing issues
     */
    public void checkScreenTime(Context context) {
        // Use the new calculator for more accurate calculation
        ScreenTimeCalculator calculator = new ScreenTimeCalculator(context);
        long dailyLimitMinutes = screenTimeRepo.getDailyLimit();
        
        // Enhanced debugging
        Log.d("ScreenTimeManager", "=== ENHANCED SCREEN TIME CHECK START ===");
        Log.d("ScreenTimeManager", "Daily limit from repository: " + dailyLimitMinutes + " minutes");
        
        // Also check SharedPreferences for comparison
        SharedPreferences prefs = context.getSharedPreferences("ParentalControlPrefs", Context.MODE_PRIVATE);
        long sharedPrefLimit = prefs.getLong("daily_limit_minutes", 120);
        Log.d("ScreenTimeManager", "Daily limit from SharedPrefs: " + sharedPrefLimit + " minutes");
        
        // Get both countdown and actual usage data for synchronization check
        ScreenTimeCalculator.ScreenTimeCountdownData countdownData = calculator.getCountdownData();
        long actualUsage = calculator.getTodayUsageMinutes();
        long countdownUsage = countdownData.usedMinutes;
        
        Log.d("ScreenTimeManager", String.format("Usage comparison - Actual: %d min, Countdown: %d min, Remaining: %d min", 
                actualUsage, countdownUsage, countdownData.remainingMinutes));
        
        // Check for synchronization issues
        long usageDifference = Math.abs(actualUsage - countdownUsage);
        if (usageDifference > 2) { // More than 2 minutes difference
            Log.w("ScreenTimeManager", String.format("⚠️ SYNC WARNING: Usage difference detected - Actual: %d, Countdown: %d (diff: %d)", 
                    actualUsage, countdownUsage, usageDifference));
        }
        
        // Use the more conservative (higher) usage value for limit checking
        long finalUsage = Math.max(actualUsage, countdownUsage);
        boolean limitExceeded = finalUsage >= dailyLimitMinutes;
        
        Log.d("ScreenTimeManager", String.format("Final usage: %d min, Limit: %d min, Exceeded: %s", 
                finalUsage, dailyLimitMinutes, limitExceeded));

        if (limitExceeded) {
            // Prevent multiple simultaneous lock attempts
            if (!lockInProgress) {
                lockInProgress = true;
                Log.d("ScreenTimeManager", "🚨 Daily limit exceeded - triggering device lock (Usage: " + finalUsage + "min, Limit: " + dailyLimitMinutes + "min)");
                
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
        } else {
            ScreenTimeCalculator.ScreenTimeData data = calculator.getScreenTimeData(dailyLimitMinutes);
            Log.d("ScreenTimeManager", "✅ Screen time within limits: " + data.toString());
            
            // Reset lock flag if we're back within limits (shouldn't happen normally)
            if (lockInProgress) {
                lockInProgress = false;
                Log.d("ScreenTimeManager", "Lock flag reset - usage is back within limits");
            }
        }
        Log.d("ScreenTimeManager", "=== ENHANCED SCREEN TIME CHECK END ===");
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
