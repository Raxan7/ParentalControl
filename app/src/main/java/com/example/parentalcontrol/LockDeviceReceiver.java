// LockDeviceReceiver.java
package com.example.parentalcontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class LockDeviceReceiver extends BroadcastReceiver {
    private static final int LOCK_TIME = 15000; // 15 seconds
    @Override
    public void onReceive(Context context, Intent intent) {
        String lockReason = intent.getStringExtra("lock_reason");
        
        if ("bedtime".equals(lockReason)) {
            Log.d("LockDeviceReceiver", "Bedtime period active - executing device lock");
            
            // Show bedtime-specific notification
            AlertNotifier.showNotification(
                    context,
                    "Bedtime Enforced",
                    "Device locked due to bedtime restrictions"
            );
        } else if ("screen_time_complete".equals(lockReason)) {
            Log.d("LockDeviceReceiver", "COMPLETE LOCKDOWN - screen time limit exceeded by more than 30 seconds");
            
            // Show more severe screen time notification with higher priority
            AlertNotifier.showNotification(
                    context,
                    "⛔ DEVICE COMPLETELY LOCKED",
                    "Your screen time limit was exceeded. Device is now completely locked. Contact parent to unlock."
            );
            
            // Make sure screen is off
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = new ComponentName(context, DeviceAdminReceiverCustom.class);
            if (devicePolicyManager.isAdminActive(adminComponentName)) {
                devicePolicyManager.lockNow();
            }
            
            // Start the screen time service if not already running to ensure overlay remains
            Intent serviceIntent = new Intent(context, ScreenTimeCountdownService.class);
            serviceIntent.putExtra("enforce_complete_lockdown", true);  // Add flag to enforce complete lockdown
            context.startService(serviceIntent);
            
            // Set up a repeating alarm to ensure the overlay stays active even if removed
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(context, ScreenTimeCheckReceiver.class);
            alarmIntent.putExtra("check_complete_lockdown", true);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1001,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // Check every minute to ensure lockdown remains active
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis(),
                    60 * 1000, // 1 minute interval
                    pendingIntent
            );
            
        } else {
            Log.d("LockDeviceReceiver", "Screen time limit exceeded - executing device lock");
            
            // Show standard screen time notification
            AlertNotifier.showNotification(
                    context,
                    "Screen Time Limit Reached",
                    "Your daily screen time limit has been reached"
            );
        }

        // Lock the device
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = new ComponentName(context, DeviceAdminReceiverCustom.class);

        if (devicePolicyManager.isAdminActive(adminComponentName)) {
            devicePolicyManager.lockNow();

            // Unlock the device after 15 seconds
            new Handler().postDelayed(() -> {
                // Unlock the device (this will allow the user to unlock the device manually)
                // No need to explicitly unlock, just allow user to unlock
            }, LOCK_TIME);
        } else {
            // Handle case where device admin is not enabled
            Toast.makeText(context, "Device admin permissions are required to lock the device.", Toast.LENGTH_LONG).show();
        }

    }
}