// AppController.java
package com.example.parentalcontrol;

import android.app.Application;
import android.content.Intent;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

public class AppController extends Application {
    private String authToken;
    private static AppController instance;
    private AppBlockerService appBlockerService;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Enable network logging
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();
                
        // Initialize blocking debugger for diagnostics
        initBlockingDebugger();
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

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String token) { this.authToken = token; }

    // Method to get the AppBlockerService instance
    public AppBlockerService getAppBlockerService() {
        return appBlockerService;
    }
    
    // Method to set the AppBlockerService instance
    public void setAppBlockerService(AppBlockerService service) {
        this.appBlockerService = service;
    }
}