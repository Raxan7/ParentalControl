package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.parentalcontrol.ServiceStartupWorker;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Use WorkManager to start services
            scheduleServiceStartup(context);
        }
    }

    private void scheduleServiceStartup(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest startupWork = new OneTimeWorkRequest.Builder(ServiceStartupWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        "ServiceStartupWork",
                        ExistingWorkPolicy.REPLACE,
                        startupWork
                );
    }
}