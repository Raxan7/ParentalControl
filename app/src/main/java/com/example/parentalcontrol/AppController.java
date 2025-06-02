// AppController.java
package com.example.parentalcontrol;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

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
    private ServiceWatchdog serviceWatchdog;
    private ServiceManager serviceManager;

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
        
        // Initialize service management
        initializeServiceManagement();
        
        // Log authentication status for debugging
        logAuthenticationStatus();
        
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
    
    /**
     * Initialize service management components
     */
    private void initializeServiceManagement() {
        try {
            // Initialize ServiceManager
            serviceManager = ServiceManager.getInstance(this);
            
            // Start essential services immediately
            serviceManager.ensureServicesRunning();
            
            // Initialize and start ServiceWatchdog
            serviceWatchdog = new ServiceWatchdog(this);
            serviceWatchdog.startWatchdog();
            
            // Schedule periodic service watchdog worker as backup
            schedulePeriodicServiceWatchdog();
            
            Log.d(TAG, "Service management initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing service management", e);
        }
    }
    
    /**
     * Schedule periodic service watchdog worker using WorkManager
     */
    private void schedulePeriodicServiceWatchdog() {
        try {
            // Create periodic work request - runs every 15 minutes (minimum allowed)
            PeriodicWorkRequest periodicWorkRequest = new PeriodicWorkRequest.Builder(
                    PeriodicServiceWatchdogWorker.class, 
                    15, TimeUnit.MINUTES)
                    .addTag("service_watchdog")
                    .build();
            
            // Schedule the work, replacing any existing work with the same unique name
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "periodic_service_watchdog",
                    ExistingPeriodicWorkPolicy.REPLACE,
                    periodicWorkRequest
            );
            
            Log.d(TAG, "Periodic service watchdog worker scheduled successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling periodic service watchdog worker", e);
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

    /**
     * Log authentication status for debugging
     */
    private void logAuthenticationStatus() {
        Log.d(TAG, "=== AUTHENTICATION STATUS ===");
        Log.d(TAG, "Auth token present: " + (authToken != null && !authToken.isEmpty()));
        Log.d(TAG, "Refresh token present: " + (refreshToken != null && !refreshToken.isEmpty()));
        
        if (authToken != null && !authToken.isEmpty()) {
            Log.d(TAG, "Auth token length: " + authToken.length());
            Log.d(TAG, "Auth token prefix: " + authToken.substring(0, Math.min(10, authToken.length())) + "...");
        }
        
        if (!isAuthenticated()) {
            Log.w(TAG, "❌ USER IS NOT AUTHENTICATED - Services will not make HTTP requests");
            Log.w(TAG, "   To fix: User needs to log in through MainActivity");
        } else {
            Log.d(TAG, "✅ USER IS AUTHENTICATED - Services can make HTTP requests");
        }
        Log.d(TAG, "=============================");
    }

    // Method to get the AppBlockerService instance
    public AppBlockerService getAppBlockerService() {
        return appBlockerService;
    }
    
    // Method to set the AppBlockerService instance
    public void setAppBlockerService(AppBlockerService service) {
        this.appBlockerService = service;
    }
    
    /**
     * Get the ServiceManager instance
     */
    public ServiceManager getServiceManager() {
        return serviceManager;
    }
    
    /**
     * Get the ServiceWatchdog instance
     */
    public ServiceWatchdog getServiceWatchdog() {
        return serviceWatchdog;
    }
    
    /**
     * Ensure all critical services are running
     */
    public void ensureServicesRunning() {
        if (serviceManager != null) {
            serviceManager.ensureServicesRunning();
        }
    }
    
    /**
     * Check if services should attempt HTTP requests
     * This provides a central place to check authentication status
     */
    public boolean canMakeHttpRequests() {
        boolean canMake = isAuthenticated();
        if (!canMake) {
            Log.w(TAG, "HTTP requests blocked - user not authenticated");
        }
        return canMake;
    }
    
    /**
     * Get a detailed status message for debugging
     */
    public String getServiceRequestStatus() {
        if (isAuthenticated()) {
            return "✅ Ready to make HTTP requests";
        } else if (refreshToken != null && !refreshToken.isEmpty()) {
            return "⚠️  No auth token but refresh token available - need to refresh";
        } else {
            return "❌ No authentication - user needs to log in";
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // Stop watchdog when app terminates
        if (serviceWatchdog != null) {
            serviceWatchdog.stopWatchdog();
        }
        
        // Cancel periodic service watchdog worker
        try {
            WorkManager.getInstance(this).cancelUniqueWork("periodic_service_watchdog");
            Log.d(TAG, "Periodic service watchdog worker cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Error cancelling periodic service watchdog worker", e);
        }
    }
}