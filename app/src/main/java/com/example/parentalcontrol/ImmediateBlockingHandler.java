package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Handles immediate checking and enforcement of blocked apps from the server
 */
public class ImmediateBlockingHandler {
    private static final String TAG = "ImmediateBlockingHandler";
    
    // Retry settings
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000; // 2 seconds
    
    /**
     * Interface for callbacks when blocking status changes
     */
    public interface BlockingStatusCallback {
        void onBlockingStatusUpdated(boolean newBlocksFound);
        void onError(Exception e);
    }
    
    /**
     * Force an immediate check for blocked apps and enforce them
     */
    @SuppressLint("HardwareIds")
    public static void forceImmediateCheckAndEnforce(Context context, BlockingStatusCallback callback) {
        forceImmediateCheckAndEnforceWithRetry(context, callback, 0);
    }
    
    /**
     * Force an immediate check with retry capability
     */
    private static void forceImmediateCheckAndEnforceWithRetry(Context context, 
                                                             BlockingStatusCallback callback,
                                                             int retryCount) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Starting immediate blocking check... (Attempt " + (retryCount + 1) + ")");

                // Get auth token
                String authToken = AppController.getInstance().getAuthToken();
                Log.d(TAG, "Auth token available: " + (authToken != null && !authToken.isEmpty()));
                
                if (authToken == null || authToken.isEmpty()) {
                    Log.w(TAG, "No authentication token available for immediate blocking check");
                    
                    // Try to recover authentication if refresh token is available
                    String refreshToken = AppController.getInstance().getRefreshToken();
                    if (refreshToken != null && !refreshToken.isEmpty()) {
                        Log.d(TAG, "Attempting to refresh authentication token before blocking check");
                        // This is a synchronous call in a background thread, so it's okay
                        try {
                            // We need to implement a synchronous version or use a different approach
                            Log.w(TAG, "Refresh token available but automatic refresh not implemented for blocking service");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to refresh token", e);
                        }
                    }
                    
                    throw new IOException("No authentication token available - user needs to log in");
                }

                // Create client with appropriate timeouts
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)  // Increased timeout
                        .readTimeout(10, TimeUnit.SECONDS)     // Increased timeout
                        .build();

                String deviceId = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );
                
                // Use the specific force-sync endpoint for immediate updates
                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/force_sync_blocked_apps/" + deviceId + "/")
                        .addHeader("Authorization", "Bearer " + authToken)
                        .post(okhttp3.RequestBody.create(new byte[0], null))
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Immediate block check response: " + responseBody);
                    
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray blockedApps = json.getJSONArray("blocked_apps");
                    
                    List<String> packageNames = new ArrayList<>();
                    for (int i = 0; i < blockedApps.length(); i++) {
                        packageNames.add(blockedApps.getString(i));
                    }
                    
                    boolean newBlocksFound = checkForNewBlocks(context, packageNames);
                    
                    // Save to local database
                    saveBlockedAppsToDatabase(context, packageNames);
                    
                    // Notify all components about the update
                    EventBus.getDefault().post(new BlockedAppsUpdatedEvent());
                    
                    // Return success
                    new Handler(Looper.getMainLooper()).post(() -> 
                            callback.onBlockingStatusUpdated(newBlocksFound));
                    
                    Log.d(TAG, "Successfully enforced " + packageNames.size() + " blocked apps");
                } else {
                    handleError(context, callback, new IOException("Error checking for blocks: " + 
                               response.code() + " - " + response.message()), retryCount);
                }
            } catch (SocketTimeoutException e) {
                Log.e(TAG, "Timeout connecting to server", e);
                // Network timeouts should be retried
                handleError(context, callback, e, retryCount);
            } catch (IOException e) {
                Log.e(TAG, "Network error during blocking check", e);
                // Network errors should be retried
                handleError(context, callback, e, retryCount);
            } catch (Exception e) {
                Log.e(TAG, "Error enforcing blocked apps", e);
                // Other errors may indicate a more serious issue
                handleError(context, callback, e, retryCount);
            }
        }).start();
    }

    /**
     * Handle errors with appropriate retry logic
     */
    private static void handleError(Context context, BlockingStatusCallback callback, 
                                  Exception error, int retryCount) {
        if (retryCount < MAX_RETRIES) {
            // Retry with exponential backoff
            int delay = RETRY_DELAY_MS * (retryCount + 1);
            Log.w(TAG, "Retry " + (retryCount + 1) + "/" + MAX_RETRIES + 
                  " in " + delay + "ms: " + error.getMessage());
            
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                forceImmediateCheckAndEnforceWithRetry(context, callback, retryCount + 1);
            }, delay);
        } else {
            // Max retries exceeded
            Log.e(TAG, "Max retries exceeded for immediate blocking check", error);
            new Handler(Looper.getMainLooper()).post(() -> callback.onError(error));
            
            // Try to recover by checking local data anyway - not ideal but better than nothing
            try {
                AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
                List<String> existingBlocks = dbHelper.getAllBlockedPackages();
                if (!existingBlocks.isEmpty()) {
                    // At least notify the components with what we have
                    Log.d(TAG, "Falling back to local database with " + existingBlocks.size() + " blocked apps");
                    EventBus.getDefault().post(new BlockedAppsUpdatedEvent());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to recover using local database", e);
            }
        }
    }
    
    /**
     * Check if there are new blocks that weren't already enforced
     */
    private static boolean checkForNewBlocks(Context context, List<String> newBlockedPackages) {
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
        List<String> existingBlocks = dbHelper.getAllBlockedPackages();
        
        // Check if any new block is not in the existing list
        for (String packageName : newBlockedPackages) {
            if (!existingBlocks.contains(packageName)) {
                return true; // Found a new block
            }
        }
        
        // Check if any existing block was removed
        for (String packageName : existingBlocks) {
            if (!newBlockedPackages.contains(packageName)) {
                return true; // A previously blocked app is now unblocked
            }
        }
        
        return false; // No changes
    }

    private static void saveBlockedAppsToDatabase(Context context, List<String> packageNames) {
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
