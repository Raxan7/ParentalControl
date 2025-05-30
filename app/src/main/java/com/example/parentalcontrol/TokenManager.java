package com.example.parentalcontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages authentication tokens with automatic refresh capabilities
 * Ensures tokens never expire by proactively refreshing them
 */
public class TokenManager {
    private static final String TAG = "TokenManager";
    private static final long REFRESH_INTERVAL_DAYS = 30; // Refresh every 30 days
    private static final long REFRESH_INTERVAL_MS = REFRESH_INTERVAL_DAYS * 24 * 60 * 60 * 1000;
    
    private static TokenManager instance;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private boolean isRefreshScheduled = false;
    
    private TokenManager() {
        refreshHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized TokenManager getInstance() {
        if (instance == null) {
            instance = new TokenManager();
        }
        return instance;
    }
    
    /**
     * Start automatic token refresh schedule
     */
    public void startAutoRefresh() {
        if (isRefreshScheduled) {
            Log.d(TAG, "Auto refresh already scheduled");
            return;
        }
        
        String refreshToken = AppController.getInstance().getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            Log.d(TAG, "No refresh token available, cannot start auto refresh");
            return;
        }
        
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                performTokenRefresh();
                // Schedule next refresh
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
        
        // Start first refresh in 1 hour
        refreshHandler.postDelayed(refreshRunnable, 60 * 60 * 1000);
        isRefreshScheduled = true;
        
        Log.d(TAG, "Auto token refresh scheduled every " + REFRESH_INTERVAL_DAYS + " days");
    }
    
    /**
     * Stop automatic token refresh
     */
    public void stopAutoRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            isRefreshScheduled = false;
            Log.d(TAG, "Auto token refresh stopped");
        }
    }
    
    /**
     * Perform immediate token refresh
     */
    public void refreshNow() {
        performTokenRefresh();
    }
    
    /**
     * Internal method to perform token refresh
     */
    private void performTokenRefresh() {
        String refreshToken = AppController.getInstance().getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            Log.e(TAG, "Cannot refresh: no refresh token available");
            return;
        }
        
        Log.d(TAG, "Performing automatic token refresh...");
        
        AuthService.refreshToken(refreshToken, new AuthService.AuthCallback() {
            @Override
            public void onSuccess(String accessToken, String refreshToken) {
                Log.d(TAG, "Token refresh successful");
                // Token is already saved by AuthService
            }
            
            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "Token refresh failed", e);
                // Don't clear tokens on failure - they might still be valid for a long time
                // Only log the error and try again on next scheduled refresh
            }
        });
    }
    
    /**
     * Validate current token (optional - for future use)
     */
    public void validateCurrentToken(TokenValidationCallback callback) {
        String token = AppController.getInstance().getAuthToken();
        if (token == null || token.isEmpty()) {
            callback.onValidationResult(false, "No token available");
            return;
        }
        
        // Simple validation by making a test API call
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();
                
                // Use a simple endpoint to validate token
                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/validate-token/")
                        .addHeader("Authorization", "Bearer " + token)
                        .build();
                
                Response response = client.newCall(request).execute();
                boolean isValid = response.isSuccessful();
                
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onValidationResult(isValid, 
                                isValid ? "Token is valid" : "Token validation failed: " + response.code()));
                
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onValidationResult(false, "Validation error: " + e.getMessage()));
            }
        }).start();
    }
    
    /**
     * Callback interface for token validation
     */
    public interface TokenValidationCallback {
        void onValidationResult(boolean isValid, String message);
    }
}
