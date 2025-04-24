// ErrorHandler.java
package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;

import java.io.IOException;

public class ErrorHandler {
    public static final String TAG = "ParentalControl";

    public static void handleError(Context context, Throwable error) {
        Log.e(TAG, "Error occurred", error);
        EventBus.getDefault().post(new ErrorEvent(error.getMessage()));
    }

    public static class ErrorEvent {
        public final String message;
        public ErrorEvent(String message) {
            this.message = message;
        }
    }

    public static void handleApiError(Context context, Exception e, String operation) {
        Log.e(TAG, "API Error during " + operation, e);

        String message = "Network error";
        if (e instanceof IOException) {
            message = "Connection failed";
        } else if (e instanceof JSONException) {
            message = "Data format error";
        }

        EventBus.getDefault().post(new ErrorEvent(operation + " failed: " + message));

        // Retry logic for critical operations
        if (operation.equals("data_sync")) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                context.startService(new Intent(context, DataSyncService.class));
            }, 5000);
        }
    }
}