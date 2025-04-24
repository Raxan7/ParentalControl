// AppUsageRepository.java
package com.example.parentalcontrol;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AppUsageRepository {
    private final AppUsageDatabaseHelper dbHelper;
    private final DataSync dataSync;

    public AppUsageRepository(Context context) {
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
        this.dataSync = new DataSync();
    }

    // In AppUsageRepository.java
    public void saveAppUsage(String packageName, long startTime, long endTime) {
        Log.d("DB", "Saving app usage: " + packageName +
                " from " + startTime + " to " + endTime);

        dbHelper.saveAppUsage(packageName, startTime, endTime);

        // Verify the save
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM app_usage WHERE app_name = ? AND start_time = ?",
                new String[]{packageName, String.valueOf(startTime)}
        );
        if (cursor.moveToFirst()) {
            Log.d("DB", "Records found: " + cursor.getInt(0));
        }
        cursor.close();
    }

    public List<AppUsage> getTodayAppUsage() {
        List<AppUsage> appUsages = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Query for today's usage
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

        String query = "SELECT app_name, start_time, end_time FROM app_usage " +
                "WHERE end_time > ? ORDER BY end_time DESC";

        try (Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(oneDayAgo)})) {
            while (cursor.moveToNext()) {
                appUsages.add(new AppUsage(
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getLong(2)
                ));
            }
        }
        return appUsages;
    }
}