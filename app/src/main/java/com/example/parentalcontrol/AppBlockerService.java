package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AppBlockerService extends Service {
    private ActivityManager activityManager;
    private Handler handler;
    private List<String> blockedPackages = new ArrayList<>();
    private static final long SYNC_INTERVAL = 30 * 60 * 1000; // 30 minutes

    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        handler = new Handler();
        startMonitoring();
        syncBlockedApps();
    }

    private void startMonitoring() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                handler.postDelayed(this, 1000); // Check every second
            }
        }, 1000);
    }

    private void syncBlockedApps() {
        new Thread(() -> {
            try {
                String authToken = AppController.getInstance().getAuthToken();
                if (authToken == null || authToken.isEmpty()) {
                    return;
                }

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );

                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/get_blocked_apps/" + deviceId + "/")
                        .addHeader("Authorization", "Bearer " + authToken)
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray blockedApps = json.getJSONArray("blocked_apps");

                    synchronized (blockedPackages) {
                        blockedPackages.clear();
                        for (int i = 0; i < blockedApps.length(); i++) {
                            blockedPackages.add(blockedApps.getString(i));
                        }
                    }

                    // Save to local database
                    saveBlockedAppsToDatabase(blockedPackages);
                }
            } catch (IOException | JSONException e) {
                Log.e("AppBlocker", "Error syncing blocked apps", e);
            }

            // Schedule next sync
            handler.postDelayed(this::syncBlockedApps, SYNC_INTERVAL);
        }).start();
    }

    private void saveBlockedAppsToDatabase(List<String> packageNames) {
        SQLiteDatabase db = new AppUsageDatabaseHelper(this).getWritableDatabase();
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
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    private void checkForegroundApp() {
        List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            String packageName = tasks.get(0).topActivity.getPackageName();

            synchronized (blockedPackages) {
                if (blockedPackages.contains(packageName)) {
                    // App is blocked - close it
                    activityManager.killBackgroundProcesses(packageName);

                    // Notify user
                    EventBus.getDefault().post(new BlockedAppEvent(packageName));
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Subscribe
    public void onNewBlockedApp(NewBlockedAppEvent event) {
        // Immediately add to blocked list
        synchronized (blockedPackages) {
            if (!blockedPackages.contains(event.packageName)) {
                blockedPackages.add(event.packageName);
            }
        }
    }
}