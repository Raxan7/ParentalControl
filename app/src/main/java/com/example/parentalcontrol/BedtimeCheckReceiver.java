// BedtimeCheckReceiver.java
package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that performs periodic bedtime checks
 * Triggered by AlarmManager to check if current time is within bedtime period
 */
public class BedtimeCheckReceiver extends BroadcastReceiver {
    private static final String TAG = "BedtimeCheckReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Performing periodic bedtime check");
        
        try {
            // Create bedtime enforcer and check current status
            BedtimeEnforcer bedtimeEnforcer = new BedtimeEnforcer(context);
            
            // Check and enforce bedtime if needed
            boolean bedtimeActive = bedtimeEnforcer.checkAndEnforceBedtime();
            
            if (bedtimeActive) {
                Log.d(TAG, "✅ Bedtime enforcement triggered");
            } else {
                Log.d(TAG, "ℹ️ No bedtime enforcement needed at this time");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during bedtime check", e);
        }
    }
}
