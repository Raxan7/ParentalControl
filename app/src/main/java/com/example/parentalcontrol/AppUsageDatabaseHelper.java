package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class AppUsageDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "AppUsage.db";
    private static final int DB_VERSION = 1;
    private final Context context;

    public AppUsageDatabaseHelper(Context context) {

        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Existing tables
        db.execSQL("CREATE TABLE app_usage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "app_name TEXT," +
                "start_time INTEGER," +
                "end_time INTEGER," +
                "sync_status INTEGER DEFAULT 0)");

        // New screen time tables
        db.execSQL("CREATE TABLE screen_time (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp TEXT," + // yyyy-MM-dd HH:mm format, not just date
                "minutes INTEGER," + // one-minute chunks
                "sync_status INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE screen_time_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "daily_limit_minutes INTEGER," +
                "bedtime_start TEXT," +
                "bedtime_end TEXT," +
                "last_updated INTEGER)");

        // Add blocked apps table
        db.execSQL("CREATE TABLE blocked_apps (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "package_name TEXT UNIQUE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Handle database upgrades
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE screen_time (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "date TEXT," +
                    "total_minutes INTEGER," +
                    "sync_status INTEGER DEFAULT 0)");
        }
    }

    // Add these new methods to AppUsageDatabaseHelper
    public void saveScreenTimeMinute(String timestamp, int minutes) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("timestamp", timestamp);
        values.put("minutes", minutes);
        db.insert("screen_time", null, values);
        db.close();
    }


    public void updateScreenTimeSyncStatus(String date, int syncStatus) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("sync_status", syncStatus);
        db.update("screen_time", values, "date = ?", new String[]{date});
        db.close();
    }

    public Cursor getUnsyncedScreenTime() {
        SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery(
                "SELECT date, total_minutes FROM screen_time WHERE sync_status = 0",
                null
        );
    }

    public void saveAppUsage(String appName, long startTime, long endTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("app_name", appName);
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        db.insert("app_usage", null, values);
        db.close();
    }

    public Context getContext() {
        return context;
    }

    public void logScreenTimeRulesData() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM screen_time_rules", null);

        if (cursor != null && cursor.moveToFirst()) {
            do {
                @SuppressLint("Range") int id = cursor.getInt(cursor.getColumnIndex("id"));
                @SuppressLint("Range") int dailyLimitMinutes = cursor.getInt(cursor.getColumnIndex("daily_limit_minutes"));
                @SuppressLint("Range") String bedtimeStart = cursor.getString(cursor.getColumnIndex("bedtime_start"));
                @SuppressLint("Range") String bedtimeEnd = cursor.getString(cursor.getColumnIndex("bedtime_end"));
                @SuppressLint("Range") long lastUpdated = cursor.getLong(cursor.getColumnIndex("last_updated"));

                // Log the data
                Log.d("ScreenTimeRulesDatabase", "ID: " + id + ", Daily Limit: " + dailyLimitMinutes +
                        " mins, Bedtime Start: " + bedtimeStart + ", Bedtime End: " + bedtimeEnd +
                        ", Last Updated: " + lastUpdated);
            } while (cursor.moveToNext());
        }

        if (cursor != null) {
            cursor.close();
        }
    }


}