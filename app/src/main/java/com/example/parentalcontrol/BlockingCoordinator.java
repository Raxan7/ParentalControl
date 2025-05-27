package com.example.parentalcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

/**
 * Flow coordinator that connects ImmediateSyncService with blocking mechanisms
 */
public class BlockingCoordinator {
    private static final String TAG = "BlockingCoordinator";
    
    /**
     * Coordinate the full app blocking flow
     * 1. Fetch blocked apps from server
     * 2. Save to local database
     * 3. Notify components via EventBus
     * 4. Enforce blocking
     */
    public static void syncAndEnforceBlocking(Context context) {
        Log.d(TAG, "Starting sync and enforce blocking flow");
        Toast.makeText(context, "Syncing blocked apps...", Toast.LENGTH_SHORT).show();
        
        // Step 1: Fetch blocked apps from server
        ImmediateSyncService.forceSyncBlockedApps(context, new ImmediateSyncService.SyncCallback() {
            @Override
            public void onSyncSuccess(List<String> blockedApps) {
                Log.d(TAG, "Sync successful, received " + blockedApps.size() + " blocked apps");
                
                // Step 2: Save to database
                saveBlockedAppsToDatabase(context, blockedApps);
                
                // Step 3: Notify components
                notifyBlockedAppsUpdated();
                
                // Show success message
                Toast.makeText(context, 
                    "Synced " + blockedApps.size() + " blocked apps", 
                    Toast.LENGTH_SHORT).show();
                
                // Step 4: Request immediate enforcement
                enforceBlocking(context, blockedApps);
            }
            
            @Override
            public void onSyncFailure(Exception error) {
                Log.e(TAG, "Sync failed", error);
                Toast.makeText(context, 
                    "Sync failed: " + error.getMessage(), 
                    Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * Save blocked app list to database
     */
    private static void saveBlockedAppsToDatabase(Context context, List<String> blockedApps) {
        Log.d(TAG, "Saving " + blockedApps.size() + " apps to database");
        
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        
        try {
            db.beginTransaction();
            
            // Clear existing blocked apps
            db.execSQL("DELETE FROM blocked_apps");
            Log.d(TAG, "Cleared existing blocked apps");
            
            // Insert new blocked apps
            for (String packageName : blockedApps) {
                ContentValues values = new ContentValues();
                values.put("package_name", packageName);
                db.insert("blocked_apps", null, values);
                Log.d(TAG, "Added blocked app: " + packageName);
            }
            
            db.setTransactionSuccessful();
            Log.d(TAG, "Database transaction successful");
        } catch (Exception e) {
            Log.e(TAG, "Error saving blocked apps to database", e);
        } finally {
            if (db != null) {
                db.endTransaction();
                db.close();
            }
        }
    }
    
    /**
     * Notify all components that blocked apps have been updated
     */
    private static void notifyBlockedAppsUpdated() {
        Log.d(TAG, "Broadcasting blocked apps updated event");
        BlockedAppsUpdatedEvent event = new BlockedAppsUpdatedEvent();
        EventBus.getDefault().post(event);
    }
    
    /**
     * Enforce blocking on currently running apps
     */
    private static void enforceBlocking(Context context, List<String> blockedApps) {
        Log.d(TAG, "Requesting immediate enforcement of blocked apps");
        
        // Option 1: Use broadcast
        Intent intent = new Intent("com.example.parentalcontrol.ENFORCE_BLOCKING");
        intent.putExtra("force_check", true);
        context.sendBroadcast(intent);
        
        // Option 2: Directly check current app through AppBlockerService
        try {
            AppBlockerService blockService = AppController.getInstance().getAppBlockerService();
            if (blockService != null) {
                blockService.checkAndBlockCurrentApp();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enforcing immediate blocking", e);
        }
    }
    
    /**
     * Test if app blocking is working properly
     */
    public static void testAppBlocking(Context context) {
        boolean accessibilityEnabled = AppBlockAccessibilityService.isAccessibilityServiceEnabled(context);
        Log.d(TAG, "Accessibility service enabled: " + accessibilityEnabled);
        
        // Show status
        Toast.makeText(context, 
            "Accessibility service " + (accessibilityEnabled ? "enabled" : "DISABLED"), 
            Toast.LENGTH_LONG).show();
        
        if (!accessibilityEnabled) {
            // Show instructions to enable accessibility
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        }
        
        // Fetch blocked apps to verify data flow
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
        List<String> blockedApps = dbHelper.getAllBlockedPackages();
        
        Toast.makeText(context, 
            "Currently blocking " + blockedApps.size() + " apps", 
            Toast.LENGTH_LONG).show();
    }
}
