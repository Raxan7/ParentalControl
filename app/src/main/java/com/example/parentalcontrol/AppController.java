// AppController.java
package com.example.parentalcontrol;

import android.app.Application;
import android.content.Intent;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

public class AppController extends Application {
    private String authToken;
    private static AppController instance;

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
}