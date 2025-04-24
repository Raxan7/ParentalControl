package com.example.parentalcontrol;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.os.Handler;
import android.os.IBinder;

import java.util.List;

public class AppBlockerService extends Service {
    private ActivityManager activityManager;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        handler = new Handler();
        startMonitoring();
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

    private void checkForegroundApp() {
        List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            String packageName = tasks.get(0).topActivity.getPackageName();
            AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);

            // Check if app is blocked
            Cursor cursor = dbHelper.getReadableDatabase().rawQuery(
                    "SELECT 1 FROM blocked_apps WHERE app_name = ?",
                    new String[]{packageName}
            );

            if (cursor.getCount() > 0) {
                // App is blocked - close it
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                am.killBackgroundProcesses(packageName);

                // Notify user
                AlertNotifier.showNotification(
                        this,
                        "App Blocked",
                        packageName + " is blocked by parental controls"
                );
            }
            cursor.close();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
