package com.example.parentalcontrol;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class DataSyncWorker extends Worker {
    private static final String TAG = "DataSyncWorker";

    public DataSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Starting data sync...");

            // Get the auth token from shared preferences or AppController
            String authToken = AppController.getInstance().getAuthToken();
            if (authToken == null || authToken.isEmpty()) {
                Log.e(TAG, "No auth token available");
                return Result.failure();
            }

            // Perform the sync
            DataSync.syncAppUsage(
                    getApplicationContext(),
                    authToken,
                    new DataSync.SyncCallback() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "Sync completed successfully");
                        }

                        @Override
                        public void onFailure(Exception e) {
                            Log.e(TAG, "Sync failed", e);
                        }
                    }
            );

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error during sync", e);
            return Result.failure();
        }
    }
}
