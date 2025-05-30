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

        // Check every minute (testing setup)
        alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                60 * 1000, // 1 minute interval
                pendingIntent
        );

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
     * Cancels any existing screen time limits
     */
    public void cancelLimits() {
        Intent intent = new Intent(context, ScreenTimeCheckReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(pendingIntent);
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
            
            Log.d("ScreenTimeManager", "Screen time limit reset complete - new limit: 120 minutes");
            
        } catch (Exception e) {
            Log.e("ScreenTimeManager", "Error resetting screen time limit", e);
        }
    }



    private static boolean lockInProgress = false;
    
    public void checkScreenTime(Context context) {
        // Use the new calculator for more accurate calculation
        ScreenTimeCalculator calculator = new ScreenTimeCalculator(context);
        long dailyLimitMinutes = screenTimeRepo.getDailyLimit();

        if (calculator.isLimitExceeded(dailyLimitMinutes)) {
            // Prevent multiple simultaneous lock attempts
            if (!lockInProgress) {
                lockInProgress = true;
                Log.d("ScreenTimeManager", "Daily limit exceeded - triggering device lock");
                
                // Trigger device lock
                Intent intent = new Intent(context, LockDeviceReceiver.class);
                context.sendBroadcast(intent);
                
                // Reset lock flag after a delay to prevent spam
                new android.os.Handler().postDelayed(() -> lockInProgress = false, 30000); // 30 seconds
            } else {
                Log.d("ScreenTimeManager", "Lock already in progress, skipping");
            }
        } else {
            ScreenTimeCalculator.ScreenTimeData data = calculator.getScreenTimeData(dailyLimitMinutes);
            Log.d("ScreenTimeManager", "Screen time check: " + data.toString());
        }
    }


}
