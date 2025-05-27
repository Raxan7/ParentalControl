package com.example.parentalcontrol;

import android.content.Context;
import android.util.Log;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import android.provider.Settings;
import android.annotation.SuppressLint;

/**
 * Service for handling immediate sync requests from the web interface
 */
public class ImmediateSyncService {
    private static final String TAG = "ImmediateSyncService";
    private static final long SYNC_TIMEOUT = 10; // 10 seconds timeout
    
    /**
     * Interface for sync callbacks
     */
    public interface SyncCallback {
        void onSyncSuccess(List<String> blockedApps);
        void onSyncFailure(Exception error);
    }
    
    /**
     * Force sync blocked apps immediately
     */
    public static void forceSyncBlockedApps(Context context, SyncCallback callback) {
        new Thread(() -> {
            try {
                String authToken = AppController.getInstance().getAuthToken();
                if (authToken == null || authToken.isEmpty()) {
                    callback.onSyncFailure(new Exception("No auth token available"));
                    return;
                }

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(SYNC_TIMEOUT, TimeUnit.SECONDS)
                        .readTimeout(SYNC_TIMEOUT, TimeUnit.SECONDS)
                        .build();

                @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );

                // Use the force sync endpoint
                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/force_sync_blocked_apps/" + deviceId + "/")
                        .addHeader("Authorization", "Bearer " + authToken)
                        .post(RequestBody.create(new byte[0])) // Empty POST body
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONObject json = new JSONObject(responseBody);
                    
                    if (json.getString("status").equals("success")) {
                        JSONArray blockedApps = json.getJSONArray("blocked_apps");
                        List<String> packageNames = new ArrayList<>();
                        
                        for (int i = 0; i < blockedApps.length(); i++) {
                            packageNames.add(blockedApps.getString(i));
                        }
                        
                        Log.d(TAG, "Force sync successful. Retrieved " + packageNames.size() + " blocked apps");
                        callback.onSyncSuccess(packageNames);
                    } else {
                        callback.onSyncFailure(new Exception("Server returned error status"));
                    }
                } else {
                    callback.onSyncFailure(new Exception("HTTP error: " + response.code()));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in force sync", e);
                callback.onSyncFailure(e);
            }
        }).start();
    }
    
    /**
     * Check for immediate updates (can be called more frequently)
     */
    public static void checkForImmediateUpdates(Context context, SyncCallback callback) {
        forceSyncBlockedApps(context, callback);
    }
}
