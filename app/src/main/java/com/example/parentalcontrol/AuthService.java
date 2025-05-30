package com.example.parentalcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
public class AuthService {
    public static final String BASE_URL = "http://192.168.1.147:8080/";
    // public static final String BASE_URL = "https://parental-control-web.onrender.com/";
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface AuthCallback {
        void onSuccess(String accessToken, String refreshToken);
        void onFailure(Exception e);
    }

    public static void login(String username, String password, AuthCallback callback) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                JSONObject json = new JSONObject();
                json.put("username", username);
                json.put("password", password);

                Request request = new Request.Builder()
                        .url(BASE_URL + "api/token/")
                        .post(RequestBody.create(json.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                Log.d("TOKEN", String.valueOf((response.isSuccessful())));
                if (response.isSuccessful()) {
                    JSONObject tokens = new JSONObject(responseBody);
                    Log.d("TOKEN", tokens.toString());

                    String accessToken = tokens.getString("access");
                    String refreshToken = tokens.getString("refresh");

                    // Store both tokens in AppController
                    AppController.getInstance().setTokens(accessToken, refreshToken);

                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onSuccess(accessToken, refreshToken));
                } else {
                    throw new IOException("Login failed: " + responseBody);
                }
            } catch (Exception e) {
                Log.e("AuthService", "Login error", e);
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onFailure(e));
            }
        }).start();
    }

    public static void refreshToken(String refreshToken, AuthCallback callback) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                JSONObject json = new JSONObject();
                json.put("refresh", refreshToken);

                Request request = new Request.Builder()
                        .url(BASE_URL + "api/token/refresh/")
                        .post(RequestBody.create(json.toString(), JSON))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                if (response.isSuccessful()) {
                    JSONObject tokens = new JSONObject(responseBody);
                    String accessToken = tokens.getString("access");
                    
                    // Update the access token in AppController
                    AppController.getInstance().setAuthToken(accessToken);

                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onSuccess(accessToken, refreshToken));
                } else {
                    throw new IOException("Token refresh failed: " + responseBody);
                }
            } catch (Exception e) {
                Log.e("AuthService", "Token refresh error", e);
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onFailure(e));
            }
        }).start();
    }
}