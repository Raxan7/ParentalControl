package com.example.parentalcontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ServiceStartupWorker extends Worker {
    private static final String TAG = "ServiceStartupWorker";
    
    public ServiceStartupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "ServiceStartupWorker executing - starting all services");
        
        try {
            // Start services from a valid context
            Context context = getApplicationContext();
            
            // Start all essential services
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(new Intent(context, ActivityTrackerService.class));
                context.startForegroundService(new Intent(context, DataSyncService.class));
                context.startForegroundService(new Intent(context, BlockingSyncService.class));
                context.startForegroundService(new Intent(context, AppBlockerService.class));
                context.startForegroundService(new Intent(context, ScreenTimeCountdownService.class));
            } else {
                context.startService(new Intent(context, ActivityTrackerService.class));
                context.startService(new Intent(context, DataSyncService.class));
                context.startService(new Intent(context, BlockingSyncService.class));
                context.startService(new Intent(context, AppBlockerService.class));
                context.startService(new Intent(context, ScreenTimeCountdownService.class));
            }
            
            Log.d(TAG, "All services started successfully via WorkManager");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error starting services via WorkManager", e);
            return Result.retry();
        }
    }
}