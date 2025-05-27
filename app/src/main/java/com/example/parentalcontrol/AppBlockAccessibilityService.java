package com.example.parentalcontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

public class AppBlockAccessibilityService extends AccessibilityService {
    private static final String TAG = "AppBlockAccessibility";
    private List<String> blockedPackages = new ArrayList<>();
    private Handler handler = new Handler();
    private static final long BLOCK_DELAY = 500; // 500ms delay before blocking
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Accessibility Service Connected");
        loadBlockedAppsFromDatabase();
        
        // Register for EventBus notifications about blocked app updates
        EventBus.getDefault().register(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                String packageName = event.getPackageName().toString();
                
                // Don't block our own app or system UI
                if (packageName.equals(getPackageName()) || 
                    packageName.equals("com.android.systemui") ||
                    packageName.equals("android")) {
                    return;
                }
                
                Log.d(TAG, "Window state changed for: " + packageName);
                
                synchronized (blockedPackages) {
                    if (blockedPackages.contains(packageName)) {
                        Log.d(TAG, "Blocked app detected: " + packageName);
                        blockApp(packageName);
                    }
                }
            }
        }
    }

    private void blockApp(String packageName) {
        // Add a small delay to ensure the app has fully launched
        handler.postDelayed(() -> {
            try {
                Log.d(TAG, "Blocking app: " + packageName);
                
                // Method 1: Go to home screen
                performGlobalAction(GLOBAL_ACTION_HOME);
                
                // Method 2: Show a blocking message
                showBlockingMessage(packageName);
                
                // Method 3: Send EventBus notification
                EventBus.getDefault().post(new BlockedAppEvent(packageName));
                
                Log.d(TAG, "Successfully blocked: " + packageName);
                
            } catch (Exception e) {
                Log.e(TAG, "Error blocking app: " + packageName, e);
            }
        }, BLOCK_DELAY);
    }
    
    private void showBlockingMessage(String packageName) {
        try {
            // Get app name for user-friendly message
            String appName = getAppNameFromPackage(packageName);
            
            // Show toast message
            Toast.makeText(this, 
                "App \"" + appName + "\" is blocked by parental controls", 
                Toast.LENGTH_LONG).show();
                
            // Optional: Launch a blocking screen activity
            Intent blockIntent = new Intent(this, AppBlockedActivity.class);
            blockIntent.putExtra("blocked_package", packageName);
            blockIntent.putExtra("blocked_app_name", appName);
            blockIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Uncomment if you want to show a full-screen blocking message
            // startActivity(blockIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error showing blocking message", e);
        }
    }
    
    private String getAppNameFromPackage(String packageName) {
        try {
            return getPackageManager()
                .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                .toString();
        } catch (Exception e) {
            return packageName; // Fallback to package name
        }
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
                    Log.d(TAG, "Loaded blocked app: " + packageName);
                }
            }
            
            cursor.close();
            db.close();
            
            Log.d(TAG, "Loaded " + blockedPackages.size() + " blocked apps from database");
        } catch (Exception e) {
            Log.e(TAG, "Error loading blocked apps from database", e);
        }
    }
    
    // EventBus subscriber to refresh blocked apps when updated
    @org.greenrobot.eventbus.Subscribe
    public void onNewBlockedApp(NewBlockedAppEvent event) {
        Log.d(TAG, "Received new blocked app event: " + event.packageName);
        loadBlockedAppsFromDatabase();
        Log.d(TAG, "Updated blocked apps list. Total: " + blockedPackages.size());
    }
    
    @org.greenrobot.eventbus.Subscribe
    public void onBlockedAppsUpdated(BlockedAppsUpdatedEvent event) {
        Log.d(TAG, "Received blocked apps updated event");
        loadBlockedAppsFromDatabase();
        Log.d(TAG, "Refreshed blocked apps list from database. Total: " + blockedPackages.size());
        
        // Ensure we're enforcing blocking rules immediately
        checkCurrentForegroundApp();
    }
    
    private void checkCurrentForegroundApp() {
        try {
            // This will immediately check the current foreground app
            // and block it if it matches our block list
            AccessibilityEvent dummyEvent = AccessibilityEvent.obtain();
            dummyEvent.setEventType(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            
            // Get current foreground package using UsageStats or other methods
            String currentPackage = getCurrentForegroundPackage();
            if (currentPackage != null) {
                dummyEvent.setPackageName(currentPackage);
                onAccessibilityEvent(dummyEvent);
            }
            
            dummyEvent.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error checking current app", e);
        }
    }
    
    private String getCurrentForegroundPackage() {
        try {
            // Multiple ways to get foreground app
            // Method 1: Use ActivityManager (legacy)
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                @SuppressWarnings("deprecation")
                android.app.ActivityManager.RunningTaskInfo foregroundTaskInfo = am.getRunningTasks(1).get(0);
                return foregroundTaskInfo.topActivity.getPackageName();
            }
            
            // Method 2: Use UsageStatsManager (Android 5.0+)
            // Requires PACKAGE_USAGE_STATS permission
            final long USAGE_STATS_INTERVAL = 1000 * 60; // 1 minute
            
            android.app.usage.UsageStatsManager usageStatsManager = 
                (android.app.usage.UsageStatsManager) getSystemService("usagestats");
            long time = System.currentTimeMillis();
            
            android.app.usage.UsageEvents usageEvents = usageStatsManager.queryEvents(
                time - USAGE_STATS_INTERVAL, time);
            android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
            String lastPackage = null;
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPackage = event.getPackageName();
                }
            }
            
            if (lastPackage != null) {
                return lastPackage;
            }
            
            // Fallback method - less reliable
            return am.getRunningAppProcesses().get(0).processName;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground package", e);
            return null;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        Log.d(TAG, "Accessibility Service Destroyed");
    }
    
    // Method to check if accessibility service is enabled
    public static boolean isAccessibilityServiceEnabled(android.content.Context context) {
        ComponentName expectedComponentName = new ComponentName(context, AppBlockAccessibilityService.class);
        String enabledServicesSetting = android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        
        if (enabledServicesSetting == null) {
            return false;
        }
        
        android.text.TextUtils.SimpleStringSplitter colonSplitter = 
            new android.text.TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);
            
            if (enabledService != null && enabledService.equals(expectedComponentName)) {
                return true;
            }
        }
        
        return false;
    }
}
