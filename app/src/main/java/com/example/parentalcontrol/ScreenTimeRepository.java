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

    public void calculateAndSaveDailyScreenTime() {
        String today = getCurrentDate();
        int totalMinutes = calculateTodayScreenTime();

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Check if record exists for today
        Cursor cursor = db.rawQuery(
                "SELECT id FROM screen_time WHERE date = ?",
                new String[]{today}
        );

        if (cursor.getCount() > 0) {
            // Update existing record
            ContentValues values = new ContentValues();
            values.put("total_minutes", totalMinutes);
            db.update("screen_time", values, "date = ?", new String[]{today});
        } else {
            // Insert new record
            dbHelper.saveDailyScreenTime(today, totalMinutes);
        }

        cursor.close();
    }

    private int calculateTodayScreenTime() {
        long startOfDay = getStartOfDay();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT SUM(end_time - start_time) FROM app_usage WHERE end_time > ?",
                new String[]{String.valueOf(startOfDay)}
        );

        int totalMilliseconds = 0;
        if (cursor.moveToFirst()) {
            totalMilliseconds = (int) cursor.getLong(0);
        }
        cursor.close();

        // Convert milliseconds to minutes
        return totalMilliseconds / (60 * 1000);
    }

    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date());
    }

    private long getStartOfDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
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
                "SELECT daily_limit_minutes FROM screen_time_rules ORDER BY last_updated DESC LIMIT 1",
                null
        );

        long dailyLimit = 120; // Default 120 minutes
        if (cursor.moveToFirst()) {
            dailyLimit = cursor.getLong(0);
        }
        cursor.close();
        db.close();
        return dailyLimit;
    }
}