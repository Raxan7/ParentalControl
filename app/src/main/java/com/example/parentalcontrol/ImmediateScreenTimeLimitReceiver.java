package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Specialized receiver for immediate screen time limit detection
 * Provides instant response when countdown service detects limit exceeded
 */
public class ImmediateScreenTimeLimitReceiver extends BroadcastReceiver {
    private static final String TAG = "ImmediateScreenTimeLimit";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Immediate screen time limit broadcast received");
        
        try {
            int usedMinutes = intent.getIntExtra("used_minutes", 0);
            int limitMinutes = intent.getIntExtra("limit_minutes", 120);
            
            Log.d(TAG, String.format("🚨 IMMEDIATE LIMIT EXCEEDED: %d/%d minutes used", 
                    usedMinutes, limitMinutes));
            
            // Create screen time manager for immediate check
            ScreenTimeManager screenTimeManager = new ScreenTimeManager(context);
            
            // Perform immediate screen time check
            screenTimeManager.checkScreenTime(context);
            
            // Send critical notification
            EnhancedAlertNotifier.showScreenTimeNotification(
                context,
                "Screen Time Limit Reached!",
                "Daily limit of " + limitMinutes + " minutes exceeded (" + usedMinutes + " minutes used). Device will lock immediately.",
                ScreenTimeCheckReceiver.NotificationPriority.CRITICAL
            );
            
            // Also trigger immediate lock broadcast
            Intent lockIntent = new Intent(context, LockDeviceReceiver.class);
            lockIntent.putExtra("lock_reason", "screen_time");
            lockIntent.putExtra("immediate", true);
            context.sendBroadcast(lockIntent);
            
            Log.d(TAG, "Immediate screen time limit enforcement triggered");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling immediate screen time limit", e);
        }
    }
}
