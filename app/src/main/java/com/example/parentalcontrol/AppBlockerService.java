package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
        
        // Register this service with the AppController
        AppController.getInstance().setAppBlockerService(this);
        
        // Load blocked apps from local database first
        loadBlockedAppsFromDatabase();
        
        startMonitoring();
        syncBlockedApps();
        
        Log.d("AppBlocker", "Service created and registered with AppController");
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

    private void loadBlockedAppsFromDatabase() {
        try {
            SQLiteDatabase db = new AppUsageDatabaseHelper(this).getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT package_name FROM blocked_apps", null);
            
            synchronized (blockedPackages) {
                blockedPackages.clear();
                while (cursor.moveToNext()) {
                    String packageName = cursor.getString(0);
                    blockedPackages.add(packageName);
                    Log.d("AppBlocker", "Loaded blocked app from database: " + packageName);
                }
            }
            
            cursor.close();
            db.close();
            
            Log.d("AppBlocker", "Loaded " + blockedPackages.size() + " blocked apps from database");
        } catch (Exception e) {
            Log.e("AppBlocker", "Error loading blocked apps from database", e);
        }
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
        // Only do basic checking if accessibility service is not available
        if (AppBlockAccessibilityService.isAccessibilityServiceEnabled(this)) {
            // Accessibility service is handling the blocking, we just log
            return;
        }
        
        List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            String packageName = tasks.get(0).topActivity.getPackageName();

            synchronized (blockedPackages) {
                if (blockedPackages.contains(packageName)) {
                    Log.d("AppBlocker", "Blocking app (fallback method): " + packageName);
                    
                    // Multiple methods to block the app
                    activityManager.killBackgroundProcesses(packageName);
                    
                    // Send user to home screen
                    Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                    homeIntent.addCategory(Intent.CATEGORY_HOME);
                    homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(homeIntent);

                    // Notify user
                    EventBus.getDefault().post(new BlockedAppEvent(packageName));
                    
                    Log.d("AppBlocker", "Successfully blocked and closed (fallback): " + packageName);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        EventBus.getDefault().unregister(this);
        // Unregister from AppController
        AppController.getInstance().setAppBlockerService(null);
        Log.d("AppBlocker", "Service destroyed and unregistered from AppController");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Subscribe
    public void onNewBlockedApp(NewBlockedAppEvent event) {
        Log.d("AppBlocker", "Received new blocked app event: " + event.packageName);
        
        // Reload blocked apps from database to get latest changes
        loadBlockedAppsFromDatabase();
        
        Log.d("AppBlocker", "Updated blocked apps list. Total blocked apps: " + blockedPackages.size());
    }
    
    @Subscribe
    public void onBlockedAppsUpdated(BlockedAppsUpdatedEvent event) {
        Log.d("AppBlocker", "Received blocked apps updated event");
        
        // Reload blocked apps from database to get latest changes
        List<String> oldBlockedPackages = new ArrayList<>(blockedPackages);
        loadBlockedAppsFromDatabase();
        
        // Find newly added blocks
        List<String> newlyAddedBlocks = new ArrayList<>();
        for (String packageName : blockedPackages) {
            if (!oldBlockedPackages.contains(packageName)) {
                newlyAddedBlocks.add(packageName);
            }
        }
        
        // Find removed blocks
        List<String> removedBlocks = new ArrayList<>();
        for (String packageName : oldBlockedPackages) {
            if (!blockedPackages.contains(packageName)) {
                removedBlocks.add(packageName);
            }
        }
        
        // Log detailed information
        Log.d("AppBlocker", "Refreshed blocked apps list from database. Total blocked apps: " + blockedPackages.size());
        
        if (!newlyAddedBlocks.isEmpty()) {
            Log.d("AppBlocker", "Newly blocked apps: " + newlyAddedBlocks.toString());
            
            // Log debugging info
            BlockingDebugger.log("New app blocks added: " + newlyAddedBlocks);
            
            // Check if any of these newly blocked apps are currently running and enforce blocking
            for (String newlyBlocked : newlyAddedBlocks) {
                enforceBlocking(newlyBlocked);
            }
        }
        
        if (!removedBlocks.isEmpty()) {
            Log.d("AppBlocker", "Unblocked apps: " + removedBlocks.toString());
            BlockingDebugger.log("Apps unblocked: " + removedBlocks);
        }
    }
    
    /**
     * Enforce blocking for a specific package immediately
     */
    private void enforceBlocking(String packageName) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            
            // Get foreground app
            String foregroundApp = getForegroundPackage();
            
            // Check if the newly blocked app is currently in foreground
            if (foregroundApp != null && foregroundApp.equals(packageName)) {
                Log.d("AppBlocker", "Enforcing immediate blocking for: " + packageName);
                BlockingDebugger.log("Enforcing immediate block on foreground app: " + packageName);
                
                // Go to home screen
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                
                // Show blocking notification
                AlertNotifier.showNotification(
                    this,
                    "App Blocked",
                    "Access to " + getAppNameFromPackage(packageName) + " has been restricted"
                );
            }
        } catch (Exception e) {
            Log.e("AppBlocker", "Error enforcing immediate blocking", e);
        }
    }
    
    /**
     * Get user-friendly app name from package name
     */
    private String getAppNameFromPackage(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (Exception e) {
            return packageName;
        }
    }
    
    /**
     * Get the package name of the foreground app
     */
    private String getForegroundPackage() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            
            // For Android 10 (API 29) and above, we can only get our own app's tasks
            // or use the UsageStatsManager which requires special permissions
            // Here we'll use a simplified approach that works in older versions
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        if (process.pkgList.length > 0) {
                            return process.pkgList[0];
                        }
                    }
                }
            }
            
            // Fallback for older versions
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Exception e) {
            Log.e("AppBlocker", "Error getting foreground app", e);
        }
        
        return null;
    }
    
    /**
     * Checks the current foreground app and blocks it if it's in the blocked list
     * Called when we need immediate enforcement after a sync
     */
    public void checkAndBlockCurrentApp() {
        try {
            String foregroundPackage = getForegroundPackage();
            if (foregroundPackage != null) {
                Log.d("AppBlocker", "Checking if we need to block current app: " + foregroundPackage);
                
                // Check if this app is blocked
                if (blockedPackages.contains(foregroundPackage)) {
                    Log.d("AppBlocker", "Current app is blocked, enforcing block: " + foregroundPackage);
                    enforceBlocking(foregroundPackage);
                }
            }
        } catch (Exception e) {
            Log.e("AppBlocker", "Error checking current app", e);
        }
    }
}