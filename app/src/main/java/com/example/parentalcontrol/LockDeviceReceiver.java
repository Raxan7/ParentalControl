// LockDeviceReceiver.java
package com.example.parentalcontrol;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.widget.Toast;

public class LockDeviceReceiver extends BroadcastReceiver {
    private static final int LOCK_TIME = 15000; // 15 seconds
    @Override
    public void onReceive(Context context, Intent intent) {
        // Implement device locking logic here
        // For example:
        // 1. Show a lockdown screen
        // 2. Disable certain apps
        // 3. Notify parents

        // Temporary implementation - just show a notification
        AlertNotifier.showNotification(
                context,
                "Screen Time Limit Reached",
                "Your daily screen time limit has been reached"
        );

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