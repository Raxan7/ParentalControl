// ScreenTimeWorker.java
package com.example.parentalcontrol;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ScreenTimeWorker extends Worker {
    public ScreenTimeWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            new ScreenTimeManager(getApplicationContext()).checkAndSyncScreenTime();
            return Result.success();
        } catch (Exception e) {
            ErrorHandler.handleApiError(getApplicationContext(), e, "screen_time_worker");
            return Result.failure();
        }
    }
}