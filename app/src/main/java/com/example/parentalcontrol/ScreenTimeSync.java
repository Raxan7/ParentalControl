// ScreenTimeSync.java
package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScreenTimeSync {
    private static final String TAG = "ScreenTimeSync";
    private final Context context;
    private final AppUsageDatabaseHelper dbHelper;

    public interface SyncCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public ScreenTimeSync(Context context) {
        this.context = context;
        this.dbHelper = ServiceLocator.getInstance(context).getDatabaseHelper();
    }

    public void syncScreenTime(SyncCallback callback) {
        new Thread(() -> {
            try {
                Cursor cursor = dbHelper.getUnsyncedScreenTime();
                JSONArray screenTimeData = new JSONArray();
                JSONArray dates = new JSONArray();

                while (cursor.moveToNext()) {
                    JSONObject entry = new JSONObject();
                    String date = cursor.getString(0);
                    int minutes = cursor.getInt(1);

                    entry.put("date", date);
                    entry.put("total_minutes", minutes);
                    screenTimeData.put(entry);
                    dates.put(date);
                }
                cursor.close();

                if (screenTimeData.length() > 0) {
                    String authToken = AppController.getInstance().getAuthToken();
                    if (authToken == null || authToken.isEmpty()) {
                        throw new Exception("No authentication token available");
                    }

                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build();

                    JSONObject payload = new JSONObject();
                    payload.put("device_id", getDeviceId());
                    payload.put("screen_time_data", screenTimeData);

                    Request request = new Request.Builder()
                            .url(AuthService.BASE_URL + "api/sync-screen-time/")
                            .addHeader("Authorization", "Bearer " + authToken)
                            .post(RequestBody.create(payload.toString(), AuthService.JSON))
                            .build();

                    Response response = client.newCall(request).execute();

                    if (response.isSuccessful()) {
                        // Mark records as synced
                        for (int i = 0; i < dates.length(); i++) {
                            String date = dates.getString(i);
                            dbHelper.updateScreenTimeSyncStatus(date, 1);
                        }
                        notifySuccess(callback);
                    } else {
                        String responseBody = response.body() != null ?
                                response.body().string() : "No response body";
                        throw new Exception("Sync failed: " + response.message() +
                                ", Body: " + responseBody);
                    }
                } else {
                    notifySuccess(callback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Screen time sync error", e);
                notifyFailure(callback, e);
            }
        }).start();
    }

    @SuppressLint("HardwareIds")
    private String getDeviceId() {
        return Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
    }

    private void notifySuccess(SyncCallback callback) {
        new Handler(Looper.getMainLooper()).post(callback::onSuccess);
    }

    private void notifyFailure(SyncCallback callback, Exception e) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
    }
}