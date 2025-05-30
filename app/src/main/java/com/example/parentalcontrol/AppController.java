// AppController.java
package com.example.parentalcontrol;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

public class AppController extends Application {
    private static final String TAG = "AppController";
    private static final String PREFS_NAME = "ParentalControlAuth";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    
    private String authToken;
    private String refreshToken;
    private static AppController instance;
    private AppBlockerService appBlockerService;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Load persisted tokens
        loadTokensFromStorage();

        // Enable network logging
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();
                
        // Initialize blocking debugger for diagnostics
        initBlockingDebugger();
        
        Log.d(TAG, "AppController initialized. Auth token present: " + (authToken != null && !authToken.isEmpty()));
    }
    
    /**
     * Load authentication tokens from persistent storage
     */
    private void loadTokensFromStorage() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            authToken = prefs.getString(KEY_AUTH_TOKEN, null);
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
            
            Log.d(TAG, "Loaded tokens from storage. Auth token present: " + (authToken != null));
        } catch (Exception e) {
            Log.e(TAG, "Error loading tokens from storage", e);
        }
    }
    
    /**
     * Save authentication tokens to persistent storage
     */
    private void saveTokensToStorage() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            
            if (authToken != null) {
                editor.putString(KEY_AUTH_TOKEN, authToken);
            } else {
                editor.remove(KEY_AUTH_TOKEN);
            }
            
            if (refreshToken != null) {
                editor.putString(KEY_REFRESH_TOKEN, refreshToken);
            } else {
                editor.remove(KEY_REFRESH_TOKEN);
            }
            
            editor.apply();
            Log.d(TAG, "Tokens saved to storage");
        } catch (Exception e) {
            Log.e(TAG, "Error saving tokens to storage", e);
        }
    }
    
    /**
     * Clear all authentication tokens
     */
    public void clearTokens() {
        authToken = null;
        refreshToken = null;
        saveTokensToStorage();
        Log.d(TAG, "All tokens cleared");
    }
    
    /**
     * Initialize the blocking debugger for diagnostics and testing
     */
    private void initBlockingDebugger() {
        try {
            BlockingDebugger.init(this);
        } catch (Exception e) {
            // If this fails, it shouldn't crash the app
            e.printStackTrace();
        }
    }

//    private void startServices() {
//
//        startService(new Intent(this, ActivityTrackerService.class));
//    }

    public static synchronized AppController getInstance() {
        return instance;
    }

    /**
     * Get the current authentication token
     */
    public String getAuthToken() { 
        return authToken; 
    }
    
    /**
     * Set the authentication token and persist it
     */
    public void setAuthToken(String token) { 
        this.authToken = token;
        saveTokensToStorage();
        Log.d(TAG, "Auth token updated and saved");
    }
    
    /**
     * Get the current refresh token
     */
    public String getRefreshToken() {
        return refreshToken;
    }
    
    /**
     * Set the refresh token and persist it
     */
    public void setRefreshToken(String token) {
        this.refreshToken = token;
        saveTokensToStorage();
        Log.d(TAG, "Refresh token updated and saved");
    }
    
    /**
     * Set both tokens at once for efficiency
     */
    public void setTokens(String accessToken, String refreshToken) {
        this.authToken = accessToken;
        this.refreshToken = refreshToken;
        saveTokensToStorage();
        Log.d(TAG, "Both tokens updated and saved");
    }
    
    /**
     * Check if we have a valid authentication token
     */
    public boolean isAuthenticated() {
        return authToken != null && !authToken.isEmpty();
    }

    // Method to get the AppBlockerService instance
    public AppBlockerService getAppBlockerService() {
        return appBlockerService;
    }
    
    // Method to set the AppBlockerService instance
    public void setAppBlockerService(AppBlockerService service) {
        this.appBlockerService = service;
    }
}