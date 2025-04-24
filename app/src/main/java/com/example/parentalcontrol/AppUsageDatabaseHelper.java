package com.example.parentalcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
        db.execSQL("CREATE TABLE app_usage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "app_name TEXT," +
                "start_time INTEGER," +
                "end_time INTEGER," +
                "sync_status INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE screen_time_rules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "daily_limit_minutes INTEGER," +
                "bedtime_start TEXT," +
                "bedtime_end TEXT," +
                "last_updated INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS app_usage");
        onCreate(db);
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
}