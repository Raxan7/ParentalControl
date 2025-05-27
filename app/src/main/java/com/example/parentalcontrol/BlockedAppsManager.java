package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manager class that handles immediate checking for blocked apps after web interface updates
 */
public class BlockedAppsManager {
    private static final String TAG = "BlockedAppsManager";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    // Test mode configuration
    private static boolean testModeEnabled = false;
    private static int testCheckInterval = 1000; // 1 second for test mode
    private static final String TEST_PACKAGE_NAME = "com.example.testapp";
    
    /**
     * Poll the force_sync_blocked_apps endpoint to check for immediate updates
     * Call this when the app receives a notification that the web interface has updated blocked apps
     */
    @SuppressLint("HardwareIds")
    public static void forceImmediateSync(Context context, String deviceId) {
        Log.d(TAG, "Force immediate sync requested for device: " + deviceId);
        
        ImmediateBlockingHandler.forceImmediateCheckAndEnforce(context, new ImmediateBlockingHandler.BlockingStatusCallback() {
            @Override
            public void onBlockingStatusUpdated(boolean newBlocksFound) {
                Log.d(TAG, "Blocking status updated. New blocks found: " + newBlocksFound);
                if (newBlocksFound) {
                    // Show notification to user that new blocks have been applied
                    AlertNotifier.showNotification(
                        context,
                        "App Blocking Updated", 
                        "The list of blocked apps has been updated from the web interface"
                    );
                }
            }
            
            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Error during immediate sync", e);
            }
        });
    }
    
    /**
     * Start a background thread that polls for app block changes every few seconds
     * This is a more aggressive approach that can be used when immediate updates are critical
     */
    public static void startRealTimeBlockPolling(Context context, int intervalSeconds) {
        Handler handler = new Handler(Looper.getMainLooper());
        
        @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
        
        Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                try {
                    pollForBlockChanges(context, deviceId);
                } finally {
                    // Schedule next run regardless of success/failure
                    handler.postDelayed(this, intervalSeconds * 1000);
                }
            }
        };
        
        // Start the polling
        handler.post(pollTask);
    }
    
    /**
     * Check if there are any block changes by polling the API
     */
    private static void pollForBlockChanges(Context context, String deviceId) {
        new Thread(() -> {
            try {
                String authToken = AppController.getInstance().getAuthToken();
                if (authToken == null || authToken.isEmpty()) {
                    Log.d(TAG, "No auth token available for polling");
                    return;
                }
                
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();
                
                Request request = new Request.Builder()
                    .url(AuthService.BASE_URL + "api/check_block_changes/" + deviceId + "/")
                    .addHeader("Authorization", "Bearer " + authToken)
                    .build();
                
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    
                    boolean hasChanges = json.optBoolean("has_changes", false);
                    if (hasChanges) {
                        Log.d(TAG, "Block changes detected! Forcing immediate sync");
                        forceImmediateSync(context, deviceId);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error polling for block changes", e);
            }
        }).start();
    }
    
    /**
     * Enable test mode to verify immediate blocking functionality
     * This will add a test app to the block list and then verify it's enforced
     */
    public static void enableTestMode(Context context, boolean enabled) {
        testModeEnabled = enabled;
        if (enabled) {
            Toast.makeText(context, "Immediate blocking test mode enabled", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "Test mode disabled", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Run a test of the immediate blocking system
     * @param context The application context
     * @param listener A listener to receive the test results
     */
    public static void testImmediateBlocking(Context context, TestResultListener listener) {
        if (!testModeEnabled) {
            listener.onTestResult(false, "Test mode not enabled");
            return;
        }
        
        @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
        
        new Thread(() -> {
            AtomicBoolean testPassed = new AtomicBoolean(false);
            String result = "";
            
            try {
                // Step 1: Add test app to blocked list via API
                boolean added = addTestAppToBlockList(context, deviceId);
                if (!added) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        listener.onTestResult(false, "Failed to add test app to block list"));
                    return;
                }
                
                // Step 2: Wait briefly to ensure server registers the change
                Thread.sleep(500);
                
                // Step 3: Force immediate sync to get the new block list
                final AtomicBoolean syncComplete = new AtomicBoolean(false);
                ImmediateBlockingHandler.forceImmediateCheckAndEnforce(context, new ImmediateBlockingHandler.BlockingStatusCallback() {
                    @Override
                    public void onBlockingStatusUpdated(boolean newBlocksFound) {
                        syncComplete.set(true);
                    }
                    
                    @Override
                    public void onError(Exception e) {
                        syncComplete.set(true);
                    }
                });
                
                // Wait for sync to complete, up to 3 seconds
                for (int i = 0; i < 30; i++) {
                    if (syncComplete.get()) {
                        break;
                    }
                    Thread.sleep(100);
                }
                
                // Step 4: Check if test app is now in the local block list
                AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(context);
                List<String> blockedApps = dbHelper.getAllBlockedPackages();
                
                testPassed.set(blockedApps.contains(TEST_PACKAGE_NAME));
                result = testPassed.get() 
                    ? "Test successful! Immediate blocking is working correctly."
                    : "Test failed: Test app was not found in local block list";
                
                // Step 5: Clean up by removing test app from block list
                removeTestAppFromBlockList(context, deviceId);
                
            } catch (Exception e) {
                Log.e(TAG, "Error during immediate blocking test", e);
                result = "Test failed with error: " + e.getMessage();
            }
            
            // Final result
            String finalResult = result;
            new Handler(Looper.getMainLooper()).post(() -> 
                listener.onTestResult(testPassed.get(), finalResult));
        }).start();
    }
    
    /**
     * Add a test app to the block list via the API
     */
    private static boolean addTestAppToBlockList(Context context, String deviceId) throws IOException, JSONException {
        String authToken = AppController.getInstance().getAuthToken();
        if (authToken == null || authToken.isEmpty()) {
            return false;
        }
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
        
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("device_id", deviceId);
        jsonBody.put("app_name", "Test App");
        jsonBody.put("package_name", TEST_PACKAGE_NAME);
        
        RequestBody body = RequestBody.create(jsonBody.toString(), JSON);
        
        Request request = new Request.Builder()
            .url(AuthService.BASE_URL + "api/block_app/")
            .addHeader("Authorization", "Bearer " + authToken)
            .post(body)
            .build();
        
        Response response = client.newCall(request).execute();
        return response.isSuccessful();
    }
    
    /**
     * Remove the test app from the block list
     */
    private static void removeTestAppFromBlockList(Context context, String deviceId) {
        // Implementation would depend on how the server handles unblocking apps
        // For now, we'll just log this step
        Log.d(TAG, "Test complete: Would remove test app from block list");
    }
    
    /**
     * Interface for test results
     */
    public interface TestResultListener {
        void onTestResult(boolean success, String message);
    }
}
