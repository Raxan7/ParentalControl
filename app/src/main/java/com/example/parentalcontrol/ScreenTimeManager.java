// ScreenTimeManager.java
package com.example.parentalcontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Calendar;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ScreenTimeManager {
    private final Context context;
    private final AlarmManager alarmManager;

    public ScreenTimeManager(Context context) {
        this.context = context;
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * Sets a daily screen time limit in minutes
     * @param maxMinutes Maximum allowed screen time in minutes
     */
    public void setDailyLimit(long maxMinutes) {
        long millis = maxMinutes * 60 * 1000;

        // Set repeating alarm for periodic checks
        Intent intent = new Intent(context, LockDeviceReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Check every 15 minutes
        alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + millis,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pendingIntent
        );

        // Sync with backend
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
        Intent intent = new Intent(context, LockDeviceReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(pendingIntent);
    }

    public void checkScreenTime(Context context) {
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Calculate today's total usage
        long todayStart = getStartOfDay();
        Cursor cursor = db.rawQuery(
                "SELECT SUM(end_time - start_time) FROM app_usage WHERE end_time > ?",
                new String[]{String.valueOf(todayStart)}
        );

        long totalUsage = 0;
        if (cursor.moveToFirst()) {
            totalUsage = cursor.getLong(0);
        }
        cursor.close();

        // Get the daily limit from local DB (should be synced from server)
        cursor = db.rawQuery(
                "SELECT daily_limit_minutes FROM screen_time_rules ORDER BY last_updated DESC LIMIT 1",
                null
        );

        long dailyLimitMillis = 120 * 60 * 1000; // Default 120 minutes
        if (cursor.moveToFirst()) {
            dailyLimitMillis = cursor.getLong(0) * 60 * 1000;
        }
        cursor.close();
        db.close();

        if (totalUsage >= dailyLimitMillis) {
            // Trigger device lock
            Intent intent = new Intent(context, LockDeviceReceiver.class);
            context.sendBroadcast(intent);
        }
    }

    private long getStartOfDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}