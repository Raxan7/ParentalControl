package com.example.parentalcontrol;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class DataSync {
    private static final String TAG = "DataSync";

    public interface SyncCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    @SuppressLint("HardwareIds")
    public static void syncAppUsage(Context context, String jwtToken, SyncCallback callback) {
        new Thread(() -> {
            SQLiteDatabase db = null;
            try {
                AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
                db = dbHelper.getWritableDatabase();

                Cursor cursor = db.rawQuery("SELECT id, app_name, start_time, end_time FROM app_usage WHERE sync_status = 0", null);

                JSONArray usageData = new JSONArray();
                ArrayList<Long> recordIds = new ArrayList<>();
                while (cursor.moveToNext()) {
                    JSONObject entry = new JSONObject();
                    entry.put("app_name", cursor.getString(1));
                    entry.put("start_time", formatDate(cursor.getLong(2)));
                    entry.put("end_time", formatDate(cursor.getLong(3)));
                    usageData.put(entry);
                    recordIds.add(cursor.getLong(0));
                }
                cursor.close();

                if (usageData.length() > 0) {
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build();

                    JSONObject payload = new JSONObject();
                    payload.put("device_id", Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
                    payload.put("usage_data", usageData);

                    Log.d(TAG, "Sync Payload: " + payload.toString()); // Log the payload

                    Request request = new Request.Builder()
                            .url(AuthService.BASE_URL + "api/sync-usage/")
                            .addHeader("Authorization", "Bearer " + jwtToken)
                            .post(RequestBody.create(payload.toString(), AuthService.JSON))
                            .build();

                    Response response = client.newCall(request).execute();

                    if (response.isSuccessful()) {
                        ContentValues values = new ContentValues();
                        values.put("sync_status", 1);

                        String whereClause = "id IN (" + TextUtils.join(",", recordIds) + ")";
                        db.update("app_usage", values, whereClause, null);

                        new Handler(Looper.getMainLooper()).post(callback::onSuccess);
                    } else {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";
                        throw new IOException("Sync failed: " + response.message() + ", Body: " + responseBody);
                    }
                } else {
                    new Handler(Looper.getMainLooper()).post(callback::onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync error", e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
            } finally {
                if (db != null && db.isOpen()) {
                    db.close();
                }
            }
        }).start();
    }

    private static String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestamp));
    }
}