package com.example.parentalcontrol;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Service that provides immediate browser redirect as backup mechanism
 * when DNS redirect alone might not be sufficient
 */
public class BrowserRedirectService extends Service {
    private static final String TAG = "BrowserRedirectService";
    private static final int REDIRECT_DELAY = 500; // 500ms delay to ensure browser is ready
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String blockedDomain = intent.getStringExtra("blocked_domain");
            String redirectUrl = intent.getStringExtra("redirect_url");
            
            // CRITICAL FIX: Check if it's Django server - don't redirect localhost to itself
            if (isDjangoServerDomain(blockedDomain)) {
                Log.d(TAG, "[BrowserRedirectService] ✅ Skipping redirect - domain is Django server: " + blockedDomain);
                stopSelf();
                return START_NOT_STICKY;
            }
            
            // CRITICAL FIX: Check if browser is active
            if (!isBrowserActive()) {
                Log.d(TAG, "[BrowserRedirectService] 📱 Browser not active, skipping redirect: " + blockedDomain);
                stopSelf();
                return START_NOT_STICKY;
            }
            
            Log.i(TAG, "[BrowserRedirectService] 🔄 Starting immediate redirect: " + blockedDomain + " → " + redirectUrl);
            
            // Perform immediate redirect with slight delay
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                performImmediateRedirect(blockedDomain, redirectUrl);
                stopSelf(); // Stop service after redirect
            }, REDIRECT_DELAY);
        }
        
        return START_NOT_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Check if the domain is Django server (prevent infinite redirect loop)
     */
    private boolean isDjangoServerDomain(String domain) {
        return DjangoServerConfig.isDjangoServerDomain(domain);
    }
    
    /**
     * Check if a browser app is currently in the foreground using multiple detection methods
     */
    private boolean isBrowserActive() {
        // Use multiple detection methods and be more permissive
        boolean method1 = isBrowserActiveViaRunningTasks();
        boolean method2 = isBrowserActiveViaRunningProcesses();
        boolean method3 = shouldAllowFallbackRedirect(); // Fallback when detection is uncertain
        
        boolean result = method1 || method2 || method3;
        
        Log.d(TAG, String.format("[isBrowserActive] Methods - Tasks: %s, Processes: %s, Fallback: %s → Result: %s", 
                method1, method2, method3, result));
        
        return result;
    }
    
    /**
     * Legacy method using getRunningTasks
     */
    private boolean isBrowserActiveViaRunningTasks() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            
            List<ActivityManager.RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);
            if (runningTasks.isEmpty()) return false;
            
            String topActivity = runningTasks.get(0).topActivity.getPackageName();
            boolean isBrowser = isBrowserPackage(topActivity);
            
            Log.d(TAG, "[isBrowserActiveViaRunningTasks] Top app: " + topActivity + ", is browser: " + isBrowser);
            return isBrowser;
            
        } catch (Exception e) {
            Log.w(TAG, "[isBrowserActiveViaRunningTasks] Error", e);
            return false;
        }
    }
    
    /**
     * Check running processes for browser applications
     */
    private boolean isBrowserActiveViaRunningProcesses() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            
            List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
            if (runningProcesses == null) return false;
            
            for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    
                    for (String processName : processInfo.pkgList) {
                        if (isBrowserPackage(processName)) {
                            Log.d(TAG, "[isBrowserActiveViaRunningProcesses] Found active browser process: " + processName);
                            return true;
                        }
                    }
                }
            }
            return false;
            
        } catch (Exception e) {
            Log.w(TAG, "[isBrowserActiveViaRunningProcesses] Error", e);
            return false;
        }
    }
    
    /**
     * Fallback when browser detection is uncertain - be more permissive during likely browsing times
     */
    private boolean shouldAllowFallbackRedirect() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        
        // Allow during reasonable browsing hours
        boolean isDuringBrowsingHours = hour >= 6 && hour <= 23;
        
        Log.d(TAG, "[shouldAllowFallbackRedirect] Current hour: " + hour + ", allowing fallback: " + isDuringBrowsingHours);
        return isDuringBrowsingHours;
    }
    
    /**
     * Check if a package name corresponds to a browser application
     */
    private boolean isBrowserPackage(String packageName) {
        if (packageName == null) return false;
        
        String lowerPackage = packageName.toLowerCase();
        
        return lowerPackage.contains("chrome") ||
               lowerPackage.contains("firefox") ||
               lowerPackage.contains("browser") ||
               lowerPackage.contains("opera") ||
               lowerPackage.contains("edge") ||
               lowerPackage.contains("samsung") ||
               lowerPackage.contains("webview") ||
               lowerPackage.contains("brave") ||
               lowerPackage.contains("vivaldi") ||
               lowerPackage.contains("dolphin") ||
               lowerPackage.contains("uc.browser") ||
               lowerPackage.contains("duckduckgo") ||
               lowerPackage.equals("com.android.browser") ||
               lowerPackage.equals("com.google.android.apps.chrome") ||
               lowerPackage.equals("org.mozilla.firefox") ||
               lowerPackage.equals("com.opera.browser") ||
               lowerPackage.equals("com.microsoft.emmx") ||
               lowerPackage.equals("com.sec.android.app.sbrowser");
    }

    /**
     * Perform the actual browser redirect
     */
    private void performImmediateRedirect(String blockedDomain, String redirectUrl) {
        try {
            // Final safety check - never redirect if already on Django server
            if (isDjangoServerDomain(blockedDomain)) {
                Log.d(TAG, "[performImmediateRedirect] ✅ Safety check: Not redirecting Django server: " + blockedDomain);
                return;
            }
            
            Log.i(TAG, "[performImmediateRedirect] 🚀 Executing immediate redirect to: " + redirectUrl);
            
            // Create strong intent to force browser to Google.com
            Intent redirectIntent = new Intent(Intent.ACTION_VIEW);
            redirectIntent.setData(android.net.Uri.parse(redirectUrl));
            redirectIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                                   Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                                   Intent.FLAG_ACTIVITY_SINGLE_TOP);
            
            // Try to start the intent
            startActivity(redirectIntent);
            
            Log.i(TAG, "[performImmediateRedirect] ✅ Redirect intent sent successfully: " + blockedDomain + " → " + redirectUrl);
            
            // Send broadcast to notify other components
            Intent broadcastIntent = new Intent("com.example.parentalcontrol.IMMEDIATE_REDIRECT");
            broadcastIntent.putExtra("blocked_domain", blockedDomain);
            broadcastIntent.putExtra("redirect_url", redirectUrl);
            sendBroadcast(broadcastIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "[performImmediateRedirect] ❌ Error performing immediate redirect", e);
        }
    }
}
