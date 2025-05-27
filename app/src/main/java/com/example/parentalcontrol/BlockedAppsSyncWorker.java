package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BlockedAppsSyncWorker extends Worker {
    private static final String TAG = "BlockedAppsSyncWorker";

    public BlockedAppsSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Starting blocked apps sync...");

            // Get the auth token from AppController
            String authToken = AppController.getInstance().getAuthToken();
            if (authToken == null || authToken.isEmpty()) {
                Log.e(TAG, "No auth token available");
                return Result.failure();
            }

            // Check if this is a forced sync (triggered by web interface update)
            boolean isForced = getInputData().getBoolean("force_sync", false);
            
            if (isForced) {
                Log.d(TAG, "Performing forced blocked apps sync!");
                // Use the ImmediateBlockingHandler for immediate enforcement
                @SuppressLint("HardwareIds")
                String deviceId = Settings.Secure.getString(
                        getApplicationContext().getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );
                
                BlockedAppsManager.forceImmediateSync(getApplicationContext(), deviceId);
            } else {
                // Perform regular periodic blocked apps sync
                syncBlockedApps(getApplicationContext(), authToken);
            }
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error during blocked apps sync", e);
            return Result.failure();
        }
    }

    @SuppressLint("HardwareIds")
    private void syncBlockedApps(Context context, String authToken) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();

            String deviceId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );

            Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/get_blocked_apps/" + deviceId + "/")
                    .addHeader("Authorization", "Bearer " + authToken)
                    .build();

            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                Log.d(TAG, "Blocked apps response: " + responseBody);
                
                JSONObject json = new JSONObject(responseBody);
                JSONArray blockedApps = json.getJSONArray("blocked_apps");
                
                // Convert JSON array to List
                List<String> packageNames = new ArrayList<>();
                for (int i = 0; i < blockedApps.length(); i++) {
                    packageNames.add(blockedApps.getString(i));
                }
                
                // Save to local database
                saveBlockedAppsToDatabase(context, packageNames);
                
                // Notify listeners via EventBus
                EventBus.getDefault().post(new BlockedAppsUpdatedEvent());
                
                Log.d(TAG, "Successfully synced " + packageNames.size() + " blocked apps");
            } else {
                Log.e(TAG, "Failed to sync blocked apps: " + response.code() + " - " + response.message());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing blocked apps", e);
        }
    }

    private void saveBlockedAppsToDatabase(Context context, List<String> packageNames) {
        SQLiteDatabase db = new AppUsageDatabaseHelper(context).getWritableDatabase();
        db.beginTransaction();
        try {
            // Clear existing blocked apps
            db.execSQL("DELETE FROM blocked_apps");

            // Insert new blocked apps
            for (String packageName : packageNames) {
                ContentValues values = new ContentValues();
                values.put("package_name", packageName);
                db.insert("blocked_apps", null, values);
            }
            db.setTransactionSuccessful();
            Log.d(TAG, "Saved " + packageNames.size() + " blocked apps to database");
        } finally {
            db.endTransaction();
            db.close();
        }
    }
}
