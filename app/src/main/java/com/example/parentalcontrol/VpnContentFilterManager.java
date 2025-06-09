package com.example.parentalcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Manager class for controlling the VPN-based content filtering
 */
public class VpnContentFilterManager {
    private static final String TAG = "VpnContentFilterManager";
    private static final int VPN_REQUEST_CODE = 1001;
    private static final int OVERLAY_REQUEST_CODE = 1002;
    
    private final Context context;
    private static boolean isVpnActive = false;
    private Activity currentActivity;
    
    public VpnContentFilterManager(Context context) {
        this.context = context;
    }
    
    /**
     * Start the VPN content filtering service
     */
    public void startContentFiltering(Activity activity) {
        this.currentActivity = activity;
        
        try {
            // First check overlay permission (needed for browser blocking overlays)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
                !Settings.canDrawOverlays(context)) {
                
                Log.d(TAG, "Requesting overlay permission for browser blocking");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivityForResult(intent, OVERLAY_REQUEST_CODE);
                return;
            }
            
            // Then check VPN permission
            Intent intent = VpnService.prepare(context);
            
            if (intent != null) {
                // VPN permission not granted, request it
                Log.d(TAG, "Requesting VPN permission");
                activity.startActivityForResult(intent, VPN_REQUEST_CODE);
            } else {
                // VPN permission already granted, start service
                Log.d(TAG, "VPN permission already granted, starting service");
                startVpnService();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN content filtering", e);
        }
    }
    
    /**
     * Handle the result of VPN or overlay permission requests
     */
    public void handleVpnPermissionResult(int requestCode, int resultCode) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "VPN permission granted, starting service");
                startVpnService();
            } else {
                Log.w(TAG, "VPN permission denied by user");
                // Show message to user that content filtering cannot work without VPN permission
            }
        } else if (requestCode == OVERLAY_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
                Settings.canDrawOverlays(context)) {
                Log.d(TAG, "Overlay permission granted, proceeding to VPN permission");
                // Now request VPN permission
                if (currentActivity != null) {
                    startContentFiltering(currentActivity);
                }
            } else {
                Log.w(TAG, "Overlay permission denied - browser overlays will not work");
                // Continue anyway, just without overlay functionality
                if (currentActivity != null) {
                    startContentFiltering(currentActivity);
                }
            }
        }
    }
    
    /**
     * Start the VPN service
     */
    private void startVpnService() {
        try {
            Intent intent = new Intent(context, SimpleDnsVpnService.class);
            context.startService(intent);
            isVpnActive = true;
            Log.i(TAG, "Simple DNS VPN service started for content filtering");
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN service", e);
        }
    }
    
    /**
     * Stop the VPN content filtering service
     */
    public void stopContentFiltering() {
        try {
            Intent intent = new Intent(context, SimpleDnsVpnService.class);
            intent.setAction("STOP_VPN");
            context.startService(intent);
            isVpnActive = false;
            Log.i(TAG, "Simple DNS VPN service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping VPN service", e);
        }
    }
    
    /**
     * Check if VPN content filtering is currently active
     */
    public static boolean isContentFilteringActive() {
        return isVpnActive;
    }
    
    /**
     * Get the VPN request code for activity result handling
     */
    public static int getVpnRequestCode() {
        return VPN_REQUEST_CODE;
    }
    
    /**
     * Get the overlay request code for activity result handling
     */
    public static int getOverlayRequestCode() {
        return OVERLAY_REQUEST_CODE;
    }
}
