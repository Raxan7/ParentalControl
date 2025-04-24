// LockDeviceReceiver.java
package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class LockDeviceReceiver extends BroadcastReceiver {
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
    }
}