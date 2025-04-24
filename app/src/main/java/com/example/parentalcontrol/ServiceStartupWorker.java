package com.example.parentalcontrol;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ServiceStartupWorker extends Worker {
    public ServiceStartupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Start services from a valid context
        Context context = getApplicationContext();
        ActivityTrackerService.start(context);
        DataSyncService.start(context);
        return Result.success();
    }
}