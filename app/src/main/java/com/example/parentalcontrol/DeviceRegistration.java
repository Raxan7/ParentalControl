package com.example.parentalcontrol;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class DeviceRegistration {
    private static final String TAG = "DeviceRegistration";

    public interface RegistrationCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public static void registerDevice(Context context, String parentToken, RegistrationCallback callback) {
        new Thread(() -> {
            try {
                @SuppressLint("HardwareIds") String deviceId = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();

                JSONObject json = new JSONObject();
                json.put("device_id", deviceId);

                Request request = new Request.Builder()
                        .url(AuthService.BASE_URL + "api/register-device/")
                        .addHeader("Authorization", "Bearer " + parentToken)
                        .post(RequestBody.create(json.toString(), AuthService.JSON))
                        .build();

                try (Response response = client.newCall(request).execute()) {

                    if (response.isSuccessful()) {
                        new Handler(Looper.getMainLooper()).post(callback::onSuccess);
                    } else {
                        throw new IOException("Registration failed: " + response.message());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Device registration error", e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
            }
        }).start();
    }
}