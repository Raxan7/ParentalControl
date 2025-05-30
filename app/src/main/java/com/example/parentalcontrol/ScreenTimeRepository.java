package com.example.parentalcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ScreenTimeRepository {
    private final AppUsageDatabaseHelper dbHelper;
    private final Context context;

    public ScreenTimeRepository(Context context) {
        this.context = context;
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
    }

    public void calculateAndSaveMinuteScreenTime() {
        String currentTimestamp = getCurrentTimestamp();
        int minutes = calculateCurrentMinuteScreenTime();

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Check if record already exists for this minute
        Cursor cursor = db.rawQuery(
                "SELECT id FROM screen_time WHERE timestamp = ?",
                new String[]{currentTimestamp}
        );

        if (cursor.getCount() > 0) {
            // Update existing record (shouldn't usually happen, but safe to handle)
            ContentValues values = new ContentValues();
            values.put("minutes", minutes);
            db.update("screen_time", values, "timestamp = ?", new String[]{currentTimestamp});
        } else {
            // Insert new record
            dbHelper.saveScreenTimeMinute(currentTimestamp, minutes);
        }

        cursor.close();
        db.close();
    }

    private int calculateCurrentMinuteScreenTime() {
        long startOfMinute = getStartOfMinute();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT SUM(end_time - start_time) FROM app_usage WHERE end_time > ?",
                new String[]{String.valueOf(startOfMinute)}
        );

        int totalMilliseconds = 0;
        if (cursor.moveToFirst()) {
            totalMilliseconds = (int) cursor.getLong(0);
        }
        cursor.close();
        db.close();

        // Convert milliseconds to minutes (round up if > 0)
        return totalMilliseconds > 0 ? 1 : 0;
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }

    private long getStartOfMinute() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public void saveScreenTimeRules(long dailyLimitMinutes) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("daily_limit_minutes", dailyLimitMinutes);
        values.put("last_updated", System.currentTimeMillis());
        db.insert("screen_time_rules", null, values);
        db.close();
    }

    public long getDailyLimit() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT daily_limit_minutes, last_updated FROM screen_time_rules ORDER BY last_updated DESC LIMIT 1",
                null
        );

        long dailyLimit = 120; // Default 120 minutes
        if (cursor.moveToFirst()) {
            dailyLimit = cursor.getLong(0);
            long lastUpdated = cursor.getLong(1);
            Log.d("ScreenTimeRepository", "Found daily limit in database: " + dailyLimit + " minutes (updated at: " + lastUpdated + ")");
        } else {
            Log.d("ScreenTimeRepository", "No daily limit found in database, using default: " + dailyLimit + " minutes");
        }
        cursor.close();
        db.close();
        
        // Also check all screen time rules for debugging
        logAllScreenTimeRules();
        
        return dailyLimit;
    }
    
    /**
     * Debug method to log all screen time rules
     */
    private void logAllScreenTimeRules() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT daily_limit_minutes, last_updated FROM screen_time_rules ORDER BY last_updated DESC",
                null
        );
        
        Log.d("ScreenTimeRepository", "=== ALL SCREEN TIME RULES ===");
        int count = 0;
        while (cursor.moveToNext()) {
            long limit = cursor.getLong(0);
            long updated = cursor.getLong(1);
            Log.d("ScreenTimeRepository", "Rule " + (++count) + ": " + limit + " minutes (updated: " + updated + ")");
        }
        Log.d("ScreenTimeRepository", "Total rules found: " + count);
        Log.d("ScreenTimeRepository", "=== END SCREEN TIME RULES ===");
        
        cursor.close();
        db.close();
    }

    /**
     * Clears all app usage data for today
     */
    public void clearTodayUsageData() {
        try {
            long startOfDay = getStartOfDay();

            // Clear app usage data from today
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int deletedRows = db.delete("app_usage",
                    "start_time >= ?",
                    new String[]{String.valueOf(startOfDay)});

            Log.d("ScreenTimeRepository", "Cleared " + deletedRows + " app usage records from today");

            // Also clear screen time minute data for today
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date());

            int deletedScreenTime = db.delete("screen_time",
                    "timestamp LIKE ?",
                    new String[]{today + "%"});

            Log.d("ScreenTimeRepository", "Cleared " + deletedScreenTime + " screen time records from today");

            db.close();

        } catch (Exception e) {
            Log.e("ScreenTimeRepository", "Error clearing today's usage data", e);
        }
    }

    /**
     * Gets the start of today in milliseconds
     */
    private long getStartOfDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
