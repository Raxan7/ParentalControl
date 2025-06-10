// MainActivity.java
package com.example.parentalcontrol;

import android.app.admin.DevicePolicyManager;
import android.app.AppOpsManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import android.Manifest;
import android.provider.Settings;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import android.os.Process;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE
    };
    private static final int USAGE_STATS_PERMISSION_REQUEST = 1002;
    private static final int USAGE_STATS_REQUEST = 1001;
    private static final int REQUEST_CODE_ENABLE_ADMIN = 1003; // Unique request code for device admin
    private static final int ACCESSIBILITY_REQUEST = 1004; // Request code for accessibility service
    private static final int BATTERY_OPTIMIZATION_REQUEST = 1005; // Request code for battery optimization
    private static final int OVERLAY_PERMISSION_REQUEST = 1006; // Request code for overlay permission

    private AppUsageRepository repository;
    private ProgressDialog progressDialog;
    private FrameLayout fragmentContainer;
    private BroadcastReceiver immediateScreenTimeLimitReceiver;

    private boolean isDeviceRegistered() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        return prefs.getBoolean("device_registered", false);
    }

    private void setDeviceRegistered() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);
        prefs.edit().putBoolean("device_registered", true).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize enhanced notification system
        EnhancedAlertNotifier.initializeChannels(this);
        
        // Register immediate screen time limit receiver
        registerImmediateScreenTimeLimitReceiver();

        fragmentContainer = findViewById(R.id.fragment_container);

        // Check if device admin is already enabled
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName adminComponentName = new ComponentName(this, DeviceAdminReceiverCustom.class);

        if (!devicePolicyManager.isAdminActive(adminComponentName)) {
            // Request admin privileges
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This app needs device admin permissions to lock the device.");
            startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
        }

        // Check critical permissions first
        if (!checkUsageStatsPermission()) {
            requestUsageStatsPermission();
            return; // Wait for onActivityResult
        }

        if (!checkPermissions()) {
            requestNeededPermissions();
            return; // Wait for onRequestPermissionsResult
        }

        // Check accessibility service permission (required for app blocking)
        if (!checkAccessibilityPermission()) {
            requestAccessibilityPermission();
            return; // Wait for onActivityResult
        }

        // Check battery optimization exemption
        if (!checkBatteryOptimizationExemption()) {
            requestBatteryOptimizationExemption();
            return; // Wait for onActivityResult
        }

        // Check overlay permission (required for app blocking overlays)
        if (!checkOverlayPermission()) {
            requestOverlayPermission();
            return; // Wait for onActivityResult
        }

        // Only proceed if we have all permissions
        initializeIfPermissionsGranted();

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == USAGE_STATS_REQUEST) {
            if (checkUsageStatsPermission()) {
                // Continue with next permission check
                if (!checkPermissions()) {
                    requestNeededPermissions();
                    return;
                }
                if (!checkAccessibilityPermission()) {
                    requestAccessibilityPermission();
                    return;
                }
                initializeIfPermissionsGranted();
            } else {
                Toast.makeText(this, "Usage stats permission required", Toast.LENGTH_LONG).show();
                finish(); // Close app if permission not granted
            }
        } else if (requestCode == ACCESSIBILITY_REQUEST) {
            if (checkAccessibilityPermission()) {
                // Continue with battery optimization check
                if (!checkBatteryOptimizationExemption()) {
                    requestBatteryOptimizationExemption();
                    return;
                }
                initializeIfPermissionsGranted();
            } else {
                Toast.makeText(this, "Accessibility permission is required for app blocking to work", Toast.LENGTH_LONG).show();
                // Don't finish the app, but warn user that blocking might not work properly
                // Continue with battery optimization check
                if (!checkBatteryOptimizationExemption()) {
                    requestBatteryOptimizationExemption();
                    return;
                }
                initializeIfPermissionsGranted();
            }
        } else if (requestCode == BATTERY_OPTIMIZATION_REQUEST) {
            if (checkBatteryOptimizationExemption()) {
                Toast.makeText(this, "Battery optimization disabled - app will run continuously", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Warning: Battery optimization is still enabled. App may be killed when inactive.", Toast.LENGTH_LONG).show();
            }
            // Continue with overlay permission check
            if (!checkOverlayPermission()) {
                requestOverlayPermission();
                return;
            }
            initializeIfPermissionsGranted();
        } else if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (checkOverlayPermission()) {
                Toast.makeText(this, "Overlay permission granted - app blocking will work properly", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Warning: Overlay permission denied. App blocking may not work properly.", Toast.LENGTH_LONG).show();
            }
            initializeIfPermissionsGranted();
        } else if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            if (resultCode == RESULT_OK) {
                // Device admin enabled
                initializeApp();
            } else {
                // User denied device admin permissions
                Toast.makeText(this, "Device admin permissions are required to lock the device.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeIfPermissionsGranted() {
        if (checkUsageStatsPermission() && checkPermissions() && checkAccessibilityPermission()) {
            AppController appController = AppController.getInstance();
            String authToken = appController.getAuthToken();
            
            if (authToken == null || authToken.isEmpty()) {
                showLoginScreen();
            } else {
                Log.d("AUTH", "Found persisted auth token, proceeding with app initialization");
                initializeApp();
            }
        }
    }

    private void initializeApp() {
        // Check if we have an auth token
        String authToken = AppController.getInstance().getAuthToken();
        if (authToken == null || authToken.isEmpty()) {
            // Handle login flow
            showLoginScreen();
            return;
        }

        // Start automatic token refresh if we have tokens
        if (AppController.getInstance().getRefreshToken() != null) {
            TokenManager.getInstance().startAutoRefresh();
            Log.d("AUTH", "Started automatic token refresh");
        }

        // Log app usage data
        AppUsageDatabaseHelper dbHelper = new AppUsageDatabaseHelper(this);
        dbHelper.logScreenTimeRulesData();

        logAllSharedPreferences();

        // Start services
        startForegroundServices();

        // Register device if needed
        if (!isDeviceRegistered()) {
            showLoading("Registering device...");
            DeviceRegistration.registerDevice(
                    this,
                    authToken,
                    new DeviceRegistration.RegistrationCallback() {
                        @Override
                        public void onSuccess() {
                            hideLoading();
                            setDeviceRegistered();
                            startDataSync();
                        }

                        @Override
                        public void onFailure(Exception e) {
                            hideLoading();
                            ErrorHandler.handleApiError(MainActivity.this, e, "device_registration");
                        }
                    });
        } else {
            startDataSync();
        }
    }

    private void startDataSync() {
        showLoading("Syncing data...");
        DataSync.syncAppUsage(
                this,
                AppController.getInstance().getAuthToken(),
                new DataSync.SyncCallback() {
                    @Override
                    public void onSuccess() {
                        hideLoading();
                        // Continue with app initialization
                        ScreenTimeManager screenTimeManager = ServiceLocator.getInstance(MainActivity.this)
                                .getScreenTimeManager(MainActivity.this);
                        screenTimeManager.setDailyLimit(120);
                        
                        // Ensure bedtime enforcement is also set up independently
                        screenTimeManager.setupBedtimeEnforcement();
                        
                        // Load the screen time countdown fragment
                        loadScreenTimeCountdownFragment();
                    }

                    @Override
                    public void onFailure(Exception e) {
                        hideLoading();
                        ErrorHandler.handleApiError(MainActivity.this, e, "data_sync");
                    }
                });
    }

    private void loadScreenTimeCountdownFragment() {
        ScreenTimeCountdownFragment fragment = new ScreenTimeCountdownFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissionsGranted(grantResults)) {
                // Basic permissions granted, now check accessibility
                if (!checkAccessibilityPermission()) {
                    requestAccessibilityPermission();
                    return;
                }
                // All permissions granted, initialize the app
                initializeApp();
            } else {
                // Some permissions denied
                Toast.makeText(this, "Some permissions were denied. App may not function properly.", Toast.LENGTH_LONG).show();
                // You might want to show another explanation and request again
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBlockedAppAttempt(BlockedAppEvent event) {
        AlertNotifier.showNotification(this, "App Blocked",
                "Attempt to open " + event.packageName + " was blocked");
    }

    private void startForegroundServices() {
        Log.d("SERVICE", "Attempting to start services using enhanced service management...");

        try {
            // Use the enhanced service management from AppController
            AppController appController = AppController.getInstance();
            if (appController != null) {
                appController.ensureServicesRunning();
                Log.d("SERVICE", "Services started successfully via enhanced management");
            } else {
                // Fallback to direct service start if AppController is not available
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(new Intent(this, ActivityTrackerService.class));
                    startForegroundService(new Intent(this, DataSyncService.class));
                    startForegroundService(new Intent(this, BlockingSyncService.class));
                    startForegroundService(new Intent(this, AppBlockerService.class));
                    startForegroundService(new Intent(this, ScreenTimeCountdownService.class));
                } else {
                    startService(new Intent(this, ActivityTrackerService.class));
                    startService(new Intent(this, DataSyncService.class));
                    startService(new Intent(this, BlockingSyncService.class));
                    startService(new Intent(this, AppBlockerService.class));
                    startService(new Intent(this, ScreenTimeCountdownService.class));
                }
                Log.d("SERVICE", "Services started successfully via fallback method");
            }
        } catch (Exception e) {
            Log.e("SERVICE", "Failed to start services", e);
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String permission : REQUIRED_PERMISSIONS) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    requestNeededPermissions();
                    return false;
                }
            }
        }
        return true;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Filter out already granted permissions
            List<String> permissionsToRequest = new ArrayList<>();
            for (String permission : REQUIRED_PERMISSIONS) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }

            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toArray(new String[0]),
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    private boolean hasAllPermissionsGranted(@NonNull int[] grantResults) {
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean checkUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e("PERMISSION", "Error checking usage stats permission", e);
            return false;
        }
    }

    private void requestUsageStatsPermission() {
        if (!checkUsageStatsPermission()) {
            startActivityForResult(
                    new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    USAGE_STATS_REQUEST
            );
        }
    }

    private boolean checkAccessibilityPermission() {
        return AppBlockAccessibilityService.isAccessibilityServiceEnabled(this);
    }

    private void requestAccessibilityPermission() {
        if (!checkAccessibilityPermission()) {
            Toast.makeText(this, 
                "Please enable the Parental Control accessibility service for app blocking to work properly", 
                Toast.LENGTH_LONG).show();
            
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivityForResult(intent, ACCESSIBILITY_REQUEST);
        }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    private boolean checkBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true; // No battery optimization on older versions
    }

    /**
     * Request to disable battery optimization for this app
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkBatteryOptimizationExemption()) {
            Toast.makeText(this, 
                "Please disable battery optimization for this app to ensure it remains active", 
                Toast.LENGTH_LONG).show();
            
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST);
            } catch (Exception e) {
                Log.e("BATTERY_OPT", "Error requesting battery optimization exemption", e);
                // Fallback to general battery optimization settings
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST);
            }
        }
    }

    /**
     * Check if overlay permission is granted for this app
     */
    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true; // No overlay permission needed on older versions
    }

    /**
     * Request overlay permission for this app
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkOverlayPermission()) {
            Toast.makeText(this, 
                "Please grant overlay permission for app blocking to work properly", 
                Toast.LENGTH_LONG).show();
            
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            } catch (Exception e) {
                Log.e("OVERLAY_PERM", "Error requesting overlay permission", e);
                // Fallback to general overlay settings
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            }
        }
    }

    private void showLoginScreen() {
        // Create and show the login fragment
        LoginFragment loginFragment = new LoginFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, loginFragment)
                .commit();
    }

    private void showLoading(String message) {
        runOnUiThread(() -> {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setCancelable(false);
            }
            progressDialog.setMessage(message);
            progressDialog.show();
        });
    }

    private void hideLoading() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });
    }

    public void attemptLogin(String username, String password) {
        showLoading("Logging in...");

        AuthService.login(username, password, new AuthService.AuthCallback() {
            @Override
            public void onSuccess(String accessToken, String refreshToken) {
                hideLoading();
                
                // Tokens are already saved by AuthService
                Log.d("AUTH", "Login successful, tokens saved persistently");
                
                // Start automatic token refresh
                TokenManager.getInstance().startAutoRefresh();
                
                // Remove login fragment
                getSupportFragmentManager().beginTransaction()
                        .remove(getSupportFragmentManager()
                                .findFragmentById(R.id.fragment_container))
                        .commit();
                initializeApp();
                schedulePeriodicSync();
            }

            @Override
            public void onFailure(Exception e) {
                hideLoading();
                Toast.makeText(MainActivity.this,
                        "Login failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void schedulePeriodicSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // Sync app usage and screen time every 15 minutes
        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                DataSyncWorker.class,
                15, // Every 15 minutes
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "data_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
        );

        // NOTE: Blocked apps sync is now handled by BlockingSyncService every 10 seconds
        // No need for WorkManager periodic task as it has 15-minute minimum interval
        Log.d("SYNC", "Blocked apps sync is handled by BlockingSyncService at 10-second intervals");

        // Additional worker for daily screen time calculation
        PeriodicWorkRequest dailyScreenTimeRequest = new PeriodicWorkRequest.Builder(
                ScreenTimeWorker.class,
                24, // Every 24 hours
                TimeUnit.HOURS
        )
                .setInitialDelay(1, TimeUnit.HOURS) // Start 1 hour after initial setup
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "daily_screen_time",
                ExistingPeriodicWorkPolicy.KEEP,
                dailyScreenTimeRequest
        );
    }

    public void logAllSharedPreferences() {
        SharedPreferences prefs = getSharedPreferences("ParentalControlPrefs", MODE_PRIVATE);

        // Get all keys from SharedPreferences
        Map<String, ?> allPrefs = prefs.getAll();

        // Log each key-value pair
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Log key and value (handling different types)
            if (value instanceof String) {
                Log.d("SharedPreferencesLog", key + ": " + value);
            } else if (value instanceof Integer) {
                Log.d("SharedPreferencesLog", key + ": " + (Integer) value);
            } else if (value instanceof Boolean) {
                Log.d("SharedPreferencesLog", key + ": " + (Boolean) value);
            } else if (value instanceof Float) {
                Log.d("SharedPreferencesLog", key + ": " + (Float) value);
            } else if (value instanceof Long) {
                Log.d("SharedPreferencesLog", key + ": " + (Long) value);
            } else {
                Log.d("SharedPreferencesLog", key + ": " + value.toString());
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_screen_time_today) {
            // Load the Screen Time Today fragment
            loadScreenTimeCountdownFragment();
            return true;
        } else if (item.getItemId() == R.id.menu_diagnostics) {
            // Launch the BlockingTesterActivity
            Intent intent = new Intent(this, BlockingTesterActivity.class);
            startActivity(intent);
            return true;
        } else if (item.getItemId() == R.id.menu_vpn_test) {
            // Launch the ContentFilterTestActivity
            Intent intent = new Intent(this, ContentFilterTestActivity.class);
            startActivity(intent);
            return true;
        } else if (item.getItemId() == R.id.menu_reset_screen_time) {
            // Reset screen time limit and usage data
            resetScreenTimeLimit();
            return true;
        } else if (item.getItemId() == R.id.menu_test_enhanced_sync) {
            // Test enhanced screen time synchronization
            testEnhancedScreenTimeSync();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * Test enhanced screen time synchronization and notification system
     */
    private void testEnhancedScreenTimeSync() {
        try {
            Log.d("MainActivity", "Starting enhanced screen time sync test...");
            
            // Show progress dialog
            showLoading("Testing enhanced sync system...");
            
            // Run tests in background thread
            new Thread(() -> {
                try {
                    EnhancedScreenTimeSyncTest syncTest = new EnhancedScreenTimeSyncTest(this);
                    syncTest.runAllTests();
                    syncTest.generateTestReport();
                    
                    runOnUiThread(() -> {
                        hideLoading();
                        Toast.makeText(this, 
                                "Enhanced sync test completed! Check logs for results.", 
                                Toast.LENGTH_LONG).show();
                    });
                    
                } catch (Exception e) {
                    Log.e("MainActivity", "Error in enhanced sync test", e);
                    runOnUiThread(() -> {
                        hideLoading();
                        Toast.makeText(this, 
                                "Enhanced sync test failed: " + e.getMessage(), 
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error starting enhanced sync test", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Resets the screen time limit and clears today's usage data
     */
    private void resetScreenTimeLimit() {
        // Show confirmation dialog
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset Screen Time Limit")
                .setMessage("This will:\n\n• Clear all app usage data for today\n• Reset your daily limit to 2 hours\n• Give you a fresh start\n\nAre you sure you want to continue?")
                .setPositiveButton("Yes, Reset", (dialog, which) -> {
                    try {
                        // Show progress
                        showLoading("Resetting screen time...");
                        
                        // Perform reset in background thread
                        new Thread(() -> {
                            try {
                                // Get screen time manager and reset
                                ScreenTimeManager screenTimeManager = ServiceLocator.getInstance(MainActivity.this)
                                        .getScreenTimeManager(MainActivity.this);
                                screenTimeManager.resetScreenTimeLimit();
                                
                                // Update UI on main thread
                                runOnUiThread(() -> {
                                    hideLoading();
                                    Toast.makeText(MainActivity.this, 
                                            "Screen time limit reset successfully!\nNew limit: 2 hours", 
                                            Toast.LENGTH_LONG).show();
                                    
                                    // Refresh the screen time countdown if it's currently displayed
                                    refreshScreenTimeDisplay();
                                });
                                
                            } catch (Exception e) {
                                Log.e("MainActivity", "Error resetting screen time", e);
                                runOnUiThread(() -> {
                                    hideLoading();
                                    Toast.makeText(MainActivity.this, 
                                            "Error resetting screen time: " + e.getMessage(), 
                                            Toast.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                        
                    } catch (Exception e) {
                        Log.e("MainActivity", "Error initiating screen time reset", e);
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    /**
     * Refreshes the screen time countdown display if currently visible
     */
    private void refreshScreenTimeDisplay() {
        try {
            // Check if ScreenTimeCountdownFragment is currently displayed
            androidx.fragment.app.Fragment currentFragment = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_container);
            
            if (currentFragment instanceof ScreenTimeCountdownFragment) {
                // Reload the fragment to show updated data
                loadScreenTimeCountdownFragment();
                Log.d("MainActivity", "Screen time countdown fragment refreshed");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error refreshing screen time display", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister the immediate screen time limit receiver
        unregisterImmediateScreenTimeLimitReceiver();
    }

    /**
     * Register receiver for immediate screen time limit detection
     */
    private void registerImmediateScreenTimeLimitReceiver() {
        try {
            IntentFilter filter = new IntentFilter("com.example.parentalcontrol.IMMEDIATE_SCREEN_TIME_LIMIT");
            
            immediateScreenTimeLimitReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int usedMinutes = intent.getIntExtra("used_minutes", 0);
                    int limitMinutes = intent.getIntExtra("limit_minutes", 120);
                    
                    Log.d("MainActivity", String.format("🚨 IMMEDIATE LIMIT DETECTED: %d/%d minutes used", 
                            usedMinutes, limitMinutes));
                    
                    // Show immediate notification
                    EnhancedAlertNotifier.showScreenTimeNotification(
                        MainActivity.this,
                        "Screen Time Limit Reached!",
                        "Daily limit of " + limitMinutes + " minutes exceeded. Device will lock now.",
                        ScreenTimeCheckReceiver.NotificationPriority.CRITICAL
                    );
                    
                    // Trigger immediate screen time check
                    try {
                        ScreenTimeManager screenTimeManager = ServiceLocator.getInstance(MainActivity.this)
                                .getScreenTimeManager(MainActivity.this);
                        screenTimeManager.checkScreenTime(MainActivity.this);
                    } catch (Exception e) {
                        Log.e("MainActivity", "Error in immediate screen time check", e);
                    }
                }
            };
            
            registerReceiver(immediateScreenTimeLimitReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Log.d("MainActivity", "Immediate screen time limit receiver registered");
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error registering immediate screen time limit receiver", e);
        }
    }
    
    /**
     * Unregister the immediate screen time limit receiver
     */
    private void unregisterImmediateScreenTimeLimitReceiver() {
        try {
            if (immediateScreenTimeLimitReceiver != null) {
                unregisterReceiver(immediateScreenTimeLimitReceiver);
                immediateScreenTimeLimitReceiver = null;
                Log.d("MainActivity", "Immediate screen time limit receiver unregistered");
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error unregistering immediate screen time limit receiver", e);
        }
    }
}