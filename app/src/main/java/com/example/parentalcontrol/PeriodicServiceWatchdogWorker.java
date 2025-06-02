package com.example.parentalcontrol;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Periodic worker that ensures all essential services are running
 * This acts as a backup mechanism to restart services if they get killed
 */
public class PeriodicServiceWatchdogWorker extends Worker {
    private static final String TAG = "PeriodicServiceWatchdog";

    public PeriodicServiceWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Periodic service watchdog executing - checking service status");
        
        try {
            Context context = getApplicationContext();
            
            // Get ServiceManager and ensure all services are running
            ServiceManager serviceManager = ServiceManager.getInstance(context);
            serviceManager.ensureServicesRunning();
            
            Log.d(TAG, "Periodic service watchdog completed successfully");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error in periodic service watchdog", e);
            // Retry on failure
            return Result.retry();
        }
    }
}
