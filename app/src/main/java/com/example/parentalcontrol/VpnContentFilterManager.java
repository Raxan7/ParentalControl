package com.example.parentalcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Manager class for controlling the VPN-based content filtering with activation verification
 */
public class VpnContentFilterManager {
    private static final String TAG = "VpnContentFilterManager";
    private static final int VPN_REQUEST_CODE = 1001;
    private static final int OVERLAY_REQUEST_CODE = 1002;
    
    // VPN Verification Configuration
    private static final int MAX_ACTIVATION_ATTEMPTS = 5;
    private static final long VPN_VERIFICATION_DELAY = 2000; // 2 seconds
    private static final long VPN_REACTIVATION_DELAY = 3000; // 3 seconds
    private static final long VPN_VERIFICATION_TIMEOUT = 15000; // 15 seconds total timeout
    
    private final Context context;
    private static boolean isVpnActive = false;
    private Activity currentActivity;
    private Handler verificationHandler;
    private int activationAttempts = 0;
    private long activationStartTime = 0;
    
    public VpnContentFilterManager(Context context) {
        this.context = context;
        this.verificationHandler = new Handler(Looper.getMainLooper());
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
                // VPN permission already granted, start service with verification
                Log.d(TAG, "VPN permission already granted, starting service with verification");
                startVpnServiceWithVerification();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN content filtering", e);
        }
    }
    
    /**
     * Handle the result of VPN or overlay permission requests with verification
     */
    public void handleVpnPermissionResult(int requestCode, int resultCode) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "VPN permission granted, starting service with verification");
                startVpnServiceWithVerification();
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
     * Start the VPN service with verification and retry mechanism
     */
    private void startVpnServiceWithVerification() {
        activationAttempts = 0;
        activationStartTime = System.currentTimeMillis();
        
        Log.i(TAG, "Starting VPN service with verification and retry mechanism");
        attemptVpnActivation();
    }
    
    /**
     * Attempt to activate the VPN and schedule verification
     */
    private void attemptVpnActivation() {
        activationAttempts++;
        
        Log.d(TAG, "VPN activation attempt " + activationAttempts + "/" + MAX_ACTIVATION_ATTEMPTS);
        
        try {
            // Start the VPN service using the enhanced logic from our fix
            Intent intent = new Intent(context, SimpleDnsVpnService.class);
            
            // Use version-specific service start logic for reliability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
                Log.d(TAG, "Started VPN service using startForegroundService() for Android 8.0+");
            } else {
                context.startService(intent);
                Log.d(TAG, "Started VPN service using startService() for Android < 8.0");
            }
            
            // Schedule verification after a delay to allow VPN to establish
            verificationHandler.postDelayed(this::verifyVpnActivation, VPN_VERIFICATION_DELAY);
            
        } catch (Exception e) {
            Log.e(TAG, "Error during VPN activation attempt " + activationAttempts, e);
            scheduleRetryIfNeeded();
        }
    }
    
    /**
     * Verify that the VPN is actually active and working
     */
    private void verifyVpnActivation() {
        Log.d(TAG, "Verifying VPN activation (attempt " + activationAttempts + ")");
        
        boolean vpnEstablished = false;
        
        try {
            // Method 1: Check for VPN network interface
            vpnEstablished = isVpnInterfaceActive();
            
            if (vpnEstablished) {
                Log.i(TAG, "SUCCESS: VPN interface verified as active");
                onVpnActivationSuccess();
                return;
            }
            
            // Method 2: Check if our VPN service is running
            if (isVpnServiceRunning()) {
                Log.d(TAG, "VPN service is running, but interface not yet established - continuing to monitor");
                
                // Give it a bit more time if we haven't exceeded timeout
                long elapsedTime = System.currentTimeMillis() - activationStartTime;
                if (elapsedTime < VPN_VERIFICATION_TIMEOUT) {
                    verificationHandler.postDelayed(this::verifyVpnActivation, VPN_VERIFICATION_DELAY);
                    return;
                }
            }
            
            Log.w(TAG, "VPN verification failed - interface not established");
            scheduleRetryIfNeeded();
            
        } catch (Exception e) {
            Log.e(TAG, "Error during VPN verification", e);
            scheduleRetryIfNeeded();
        }
    }
    
    /**
     * Check if VPN network interface is active
     */
    private boolean isVpnInterfaceActive() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String name = networkInterface.getName().toLowerCase();
                
                // Look for VPN interface indicators
                if ((name.contains("tun") || name.contains("vpn") || name.contains("ppp")) && 
                    networkInterface.isUp() && !networkInterface.isLoopback()) {
                    
                    Log.d(TAG, "Found active VPN interface: " + name);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking VPN interface", e);
        }
        
        return false;
    }
    
    /**
     * Check if our VPN service is running
     */
    private boolean isVpnServiceRunning() {
        try {
            // Check if VPN service is prepared (indicates it's running)
            Intent intent = VpnService.prepare(context);
            boolean isRunning = (intent == null); // null means VPN is already running
            
            if (isRunning) {
                Log.d(TAG, "VPN service confirmed as running");
            } else {
                Log.d(TAG, "VPN service not detected as running");
            }
            
            return isRunning;
        } catch (Exception e) {
            Log.e(TAG, "Error checking VPN service status", e);
            return false;
        }
    }
    
    /**
     * Handle successful VPN activation
     */
    private void onVpnActivationSuccess() {
        isVpnActive = true;
        activationAttempts = 0;
        
        Log.i(TAG, "✅ VPN SUCCESSFULLY ACTIVATED - Content filtering is now active");
        
        // Optional: Show success notification to user
        if (currentActivity != null) {
            currentActivity.runOnUiThread(() -> {
                // You can add a Toast or other UI feedback here if desired
                Log.i(TAG, "VPN activation confirmed - parental controls are now active");
            });
        }
    }
    
    /**
     * Schedule retry if we haven't exceeded maximum attempts
     */
    private void scheduleRetryIfNeeded() {
        long elapsedTime = System.currentTimeMillis() - activationStartTime;
        
        if (activationAttempts < MAX_ACTIVATION_ATTEMPTS && elapsedTime < VPN_VERIFICATION_TIMEOUT) {
            Log.w(TAG, "Scheduling VPN reactivation in " + VPN_REACTIVATION_DELAY + "ms (attempt " + 
                  (activationAttempts + 1) + "/" + MAX_ACTIVATION_ATTEMPTS + ")");
            
            verificationHandler.postDelayed(this::attemptVpnActivation, VPN_REACTIVATION_DELAY);
        } else {
            Log.e(TAG, "❌ VPN ACTIVATION FAILED - Exceeded maximum attempts or timeout");
            onVpnActivationFailure();
        }
    }
    
    /**
     * Handle VPN activation failure
     */
    private void onVpnActivationFailure() {
        isVpnActive = false;
        activationAttempts = 0;
        
        Log.e(TAG, "VPN activation failed after " + MAX_ACTIVATION_ATTEMPTS + " attempts");
        
        // Optional: Show failure notification to user
        if (currentActivity != null) {
            currentActivity.runOnUiThread(() -> {
                Log.e(TAG, "Failed to activate VPN - parental controls may not work properly");
                // You can add user notification here (Toast, Dialog, etc.)
            });
        }
    }
    
    /**
     * Start the VPN service (legacy method - kept for compatibility)
     */
    private void startVpnService() {
        try {
            Intent intent = new Intent(context, SimpleDnsVpnService.class);
            
            // Use enhanced service starting logic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            
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
