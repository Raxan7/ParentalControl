// ScreenTimeCheckReceiver.java
package com.example.parentalcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BroadcastReceiver that performs periodic screen time checks
 * This receiver checks if the daily limit has been exceeded and only then triggers the lock
 */
public class ScreenTimeCheckReceiver extends BroadcastReceiver {
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("ScreenTimeCheckReceiver", "Performing periodic screen time check");
        
        try {
            // Create screen time manager to perform the check
            ScreenTimeManager screenTimeManager = new ScreenTimeManager(context);
            
            // Perform the actual screen time check
            screenTimeManager.checkScreenTime(context);
            
        } catch (Exception e) {
            Log.e("ScreenTimeCheckReceiver", "Error during screen time check", e);
        }
    }
}
